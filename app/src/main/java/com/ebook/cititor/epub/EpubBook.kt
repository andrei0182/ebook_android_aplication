package com.ebook.cititor.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * Minimal EPUB 2/3 reader: parses container.xml, the OPF package (metadata, manifest, spine),
 * and the table of contents (EPUB 3 nav document or EPUB 2 NCX). Resources are read on demand
 * straight from the zip file.
 */
class EpubBook private constructor(val file: File, private val zip: ZipFile) : Closeable {

    data class SpineItem(val path: String, val mediaType: String)
    data class TocEntry(val title: String, val spineIndex: Int, val depth: Int)

    /** A chapter prepared for display and for text-to-speech. */
    class Chapter(val html: String, val paragraphs: List<String>)

    var title: String = ""
        private set
    var author: String = ""
        private set
    var language: String? = null
        private set
    var coverPath: String? = null
        private set
    val spine = mutableListOf<SpineItem>()
    val toc = mutableListOf<TocEntry>()
    private val mediaTypes = HashMap<String, String>()

    fun entryBytes(path: String): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun mediaTypeOf(path: String): String = mediaTypes[path] ?: guessMime(path)

    fun spineIndexOf(path: String): Int = spine.indexOfFirst { it.path == path }

    fun chapterTitle(index: Int): String {
        toc.firstOrNull { it.spineIndex == index }?.let { return it.title }
        // Use the closest preceding TOC entry, if any.
        toc.filter { it.spineIndex in 0..index }.maxByOrNull { it.spineIndex }?.let { return it.title }
        return "Secțiunea ${index + 1}"
    }

    /** Loads a chapter; [headExtra] is injected at the end of <head> (styles and scripts). */
    fun loadChapter(index: Int, headExtra: String = ""): Chapter {
        val item = spine[index]
        val bytes = entryBytes(item.path) ?: return Chapter("<html><body></body></html>", emptyList())
        return renderHtml(String(bytes, Charsets.UTF_8), headExtra)
    }

    fun isHtml(path: String): Boolean {
        val type = mediaTypeOf(path)
        return type.contains("html")
    }

    override fun close() = zip.close()

    // ---------------------------------------------------------------- parsing

    private fun parse() {
        val container = parseXml("META-INF/container.xml")
            ?: throw IllegalArgumentException("Lipsește META-INF/container.xml")
        val opfPath = container.allElements.firstOrNull { it.local() == "rootfile" }?.attr("full-path")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Nu s-a găsit fișierul OPF")
        val opf = parseXml(opfPath) ?: throw IllegalArgumentException("OPF lipsă: $opfPath")
        val opfDir = opfPath.substringBeforeLast('/', "")

        title = opf.firstLocal("title")?.text()?.trim().orEmpty()
        author = opf.allElements.filter { it.local() == "creator" }.joinToString(", ") { it.text().trim() }
        language = opf.firstLocal("language")?.text()?.trim()?.takeIf { it.isNotEmpty() }

        data class Item(val id: String, val path: String, val type: String, val props: String)
        val items = LinkedHashMap<String, Item>()
        opf.allElements.filter { it.local() == "item" }.forEach { el ->
            val path = resolvePath(opfDir, el.attr("href"))
            val item = Item(el.attr("id"), path, el.attr("media-type"), el.attr("properties"))
            items[item.id] = item
            mediaTypes[path] = item.type
        }

        val spineEl = opf.firstLocal("spine")
        spineEl?.children()?.filter { it.local() == "itemref" }?.forEach { ref ->
            items[ref.attr("idref")]?.let { spine.add(SpineItem(it.path, it.type)) }
        }
        if (spine.isEmpty()) throw IllegalArgumentException("Cartea nu are conținut (spine gol)")

        // Cover image
        val coverItem = items.values.firstOrNull { it.props.split(' ').contains("cover-image") }
            ?: opf.allElements.firstOrNull { it.local() == "meta" && it.attr("name") == "cover" }
                ?.attr("content")?.let { items[it] }
            ?: items.values.firstOrNull { it.type.startsWith("image/") && it.id.contains("cover", true) }
        coverPath = coverItem?.takeIf { it.type.startsWith("image/") }?.path

        // Table of contents
        val nav = items.values.firstOrNull { it.props.split(' ').contains("nav") }
        if (nav != null) runCatching { parseNav(nav.path) }
        if (toc.isEmpty()) {
            val ncx = spineEl?.attr("toc")?.let { items[it] }
                ?: items.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
            if (ncx != null) runCatching { parseNcx(ncx.path) }
        }
    }

    private fun parseNav(path: String) {
        val bytes = entryBytes(path) ?: return
        val doc = Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser())
        val navs = doc.allElements.filter { it.local() == "nav" }
        val nav = navs.firstOrNull { it.attr("epub:type") == "toc" } ?: navs.firstOrNull() ?: return
        val dir = path.substringBeforeLast('/', "")
        nav.allElements.filter { it.local() == "a" }.forEach { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@forEach
            val idx = spineIndexOf(resolvePath(dir, href))
            val depth = a.parents().count { it.local() == "ol" || it.local() == "ul" } - 1
            val text = a.text().trim()
            if (idx >= 0 && text.isNotEmpty()) toc.add(TocEntry(text, idx, depth.coerceAtLeast(0)))
        }
    }

    private fun parseNcx(path: String) {
        val doc = parseXml(path) ?: return
        val dir = path.substringBeforeLast('/', "")
        doc.allElements.filter { it.local() == "navPoint" }.forEach { np ->
            val label = np.children().firstOrNull { it.local() == "navLabel" }?.text()?.trim().orEmpty()
            val src = np.children().firstOrNull { it.local() == "content" }?.attr("src").orEmpty()
            if (src.isBlank()) return@forEach
            val idx = spineIndexOf(resolvePath(dir, src))
            val depth = np.parents().count { it.local() == "navPoint" }
            if (idx >= 0 && label.isNotEmpty()) toc.add(TocEntry(label, idx, depth))
        }
    }

    private fun parseXml(path: String): Document? {
        val bytes = entryBytes(path) ?: return null
        return Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser())
    }

    companion object {
        private const val BLOCKS = "p,h1,h2,h3,h4,h5,h6,li,blockquote,pre,dt,dd,figcaption,td,th"

        fun open(file: File): EpubBook {
            val zip = ZipFile(file)
            try {
                return EpubBook(file, zip).also { it.parse() }
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }

        /**
         * Parses chapter HTML, tags every readable block with `data-tts="N"` and returns the
         * annotated HTML together with the text of each block, so the reader highlight and the
         * text-to-speech index always refer to the same paragraphs.
         */
        fun renderHtml(source: String, headExtra: String): Chapter {
            val doc = Jsoup.parse(source)
            val body = doc.body()
            var blocks: List<Element> = body.select(BLOCKS).filter { el ->
                el.text().isNotBlank() && el.parents().none { it.`is`(BLOCKS) }
            }
            if (blocks.isEmpty()) {
                val withText = body.allElements.filter { it !== body && it.ownText().isNotBlank() }
                val set = withText.toHashSet()
                blocks = withText.filter { el -> el.parents().none { it in set } }
                if (blocks.isEmpty() && body.text().isNotBlank()) blocks = listOf(body)
            }
            val paragraphs = blocks.mapIndexed { i, el ->
                el.attr("data-tts", i.toString())
                el.text().trim()
            }
            doc.head().append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            if (headExtra.isNotEmpty()) doc.head().append(headExtra)
            doc.outputSettings().prettyPrint(false)
            return Chapter(doc.outerHtml(), paragraphs)
        }

        fun resolvePath(baseDir: String, href: String): String {
            val clean = decodePercent(href.substringBefore('#').substringBefore('?'))
            if (clean.isEmpty()) return baseDir
            val parts = ArrayList<String>()
            val full = if (clean.startsWith("/")) clean.drop(1) else if (baseDir.isEmpty()) clean else "$baseDir/$clean"
            for (seg in full.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/")
        }

        fun decodePercent(s: String): String {
            if (!s.contains('%')) return s
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex); i += 3; continue
                    }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString("UTF-8")
        }

        fun guessMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "xhtml", "xht" -> "application/xhtml+xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }

        private fun Element.local(): String = tagName().substringAfter(':')
        private fun Document.firstLocal(name: String): Element? = allElements.firstOrNull { it.local() == name }
    }
}
