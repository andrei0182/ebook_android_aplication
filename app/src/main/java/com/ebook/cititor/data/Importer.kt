package com.ebook.cititor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.ebook.cititor.epub.EpubWriter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class SourceKind { EPUB, PDF, TXT, WEB, TEXT }

/** Converts the supported sources into EPUB files. All functions block: call them off the main thread. */
object Importer {

    class Result(val file: File, val kind: SourceKind, val fallbackTitle: String)

    fun fromUri(ctx: Context, uri: Uri, dest: File): Result {
        val name = displayName(ctx, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = name.substringBeforeLast('.')
        val tmp = File(dest.parentFile, dest.name + ".src")
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Fișierul nu poate fi deschis" }
            tmp.outputStream().use { input.copyTo(it) }
        }
        try {
            return when {
                ext == "pdf" || mime == "application/pdf" -> {
                    pdfToEpub(ctx, tmp, dest, base); Result(dest, SourceKind.PDF, base)
                }
                ext == "txt" || ext == "md" || mime.startsWith("text/") -> {
                    textToEpub(decodeText(tmp.readBytes()), base, dest); Result(dest, SourceKind.TXT, base)
                }
                else -> {
                    if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true) }
                    Result(dest, SourceKind.EPUB, base)
                }
            }
        } finally {
            tmp.delete()
        }
    }

    fun fromText(text: String, dest: File): Result {
        val firstLine = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        val title = if (firstLine.length > 60) firstLine.take(57).trimEnd() + "…" else firstLine.ifEmpty { "Text" }
        textToEpub(text, title, dest)
        return Result(dest, SourceKind.TEXT, title)
    }

    fun fromUrl(url: String, dest: File): Result {
        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            .timeout(20_000)
            .followRedirects(true)
            .get()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().ifBlank { url }
        val author = doc.selectFirst("meta[name=author]")?.attr("content")
            ?: doc.selectFirst("meta[property=article:author]")?.attr("content")
            ?: (URL(url).host.removePrefix("www."))
        val lang = doc.selectFirst("html")?.attr("lang")?.takeIf { it.isNotBlank() }
        val imageUrl = doc.selectFirst("meta[property=og:image]")?.absUrl("content")

        val blocks = extractArticle(doc.body())
        require(blocks.isNotEmpty()) { "Nu am găsit text în pagină" }
        val cover = imageUrl?.takeIf { it.isNotBlank() }?.let { runCatching { download(it, 5_000_000) }.getOrNull() }
        EpubWriter.write(
            dest, title, author, lang,
            listOf(EpubWriter.Section(title, listOf(EpubWriter.Block(title, heading = true)) + blocks)),
            cover = cover?.first, coverType = cover?.second ?: "image/jpeg",
        )
        return Result(dest, SourceKind.WEB, title)
    }

    /** Finds the main content of a web page (a light "reader mode"). */
    fun extractArticle(body: Element): List<EpubWriter.Block> {
        body.select("script,style,noscript,nav,header,footer,aside,form,iframe,svg,button,[aria-hidden=true],.share,.comments,#comments").remove()
        val root = body.selectFirst("article")
            ?: body.selectFirst("main, [role=main]")
            ?: body.select("div, section").maxByOrNull { el ->
                el.children().filter { it.normalName() == "p" }.sumOf { it.text().length }
            }
            ?: body
        val selector = "h1,h2,h3,h4,p,li,blockquote,pre"
        return root.select(selector)
            .filter { el -> el.parents().none { it.`is`(selector) } }
            .mapNotNull { el ->
                val text = el.text().trim()
                if (text.isEmpty()) null
                else EpubWriter.Block(text, heading = el.normalName().matches(Regex("h[1-4]")))
            }
    }

    private fun textToEpub(text: String, title: String, dest: File) {
        val paras = EpubWriter.paragraphs(text)
        require(paras.isNotEmpty()) { "Textul este gol" }
        EpubWriter.write(dest, title, "", null, EpubWriter.sectionsFromParagraphs(paras, "Partea"))
    }

    private fun pdfToEpub(ctx: Context, pdf: File, dest: File, fallbackTitle: String) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        PDDocument.load(pdf).use { doc ->
            val info = doc.documentInformation
            val title = info?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val author = info?.author.orEmpty()
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                paragraphEnd = "\n\n"
            }
            val pages = doc.numberOfPages
            val perSection = 10
            val sections = ArrayList<EpubWriter.Section>()
            var start = 1
            while (start <= pages) {
                val end = minOf(pages, start + perSection - 1)
                stripper.startPage = start
                stripper.endPage = end
                val paras = EpubWriter.paragraphs(stripper.getText(doc))
                if (paras.isNotEmpty()) {
                    val label = if (start == end) "Pagina $start" else "Paginile $start–$end"
                    sections.add(EpubWriter.Section(label, paras.map { EpubWriter.Block(it) }))
                }
                start = end + 1
            }
            require(sections.isNotEmpty()) { "PDF-ul nu conține text (poate este scanat)" }
            val cover = runCatching { renderPdfCover(pdf) }.getOrNull()
            EpubWriter.write(dest, title, author, null, sections, cover)
        }
    }

    private fun renderPdfCover(pdf: File): ByteArray {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val w = 600
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, 1600)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    return out.toByteArray()
                }
            }
        }
    }

    /** UTF-8 first; falls back to Windows-1250 (common for older Romanian text files). */
    fun decodeText(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1250"))
        }
    }

    private fun download(url: String, limit: Int): Pair<ByteArray, String> {
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            val type = conn.contentType ?: "image/jpeg"
            require(type.startsWith("image/")) { "not an image" }
            val bytes = conn.inputStream.use { it.readNBytesCompat(limit) }
            return bytes to (if (type.contains("png")) "image/png" else "image/jpeg")
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            require(out.size() <= limit) { "too large" }
        }
        return out.toByteArray()
    }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
