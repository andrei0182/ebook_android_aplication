package com.ebook.cititor.epub

import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a simple EPUB 3 from plain text, so PDFs, text files and web articles share the EPUB pipeline. */
object EpubWriter {

    class Block(val text: String, val heading: Boolean = false)
    class Section(val title: String, val blocks: List<Block>)

    fun write(
        dest: File,
        title: String,
        author: String,
        language: String?,
        sections: List<Section>,
        cover: ByteArray? = null,
        coverType: String = "image/jpeg",
    ) {
        require(sections.isNotEmpty()) { "Nu există text de importat" }
        val lang = language ?: "und"
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            // "mimetype" must be the first entry, stored uncompressed.
            val mt = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            })
            zip.write(mt); zip.closeEntry()

            fun putBytes(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }

            putBytes(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".toByteArray()
            )

            val manifest = StringBuilder()
            val spine = StringBuilder()
            val nav = StringBuilder()
            sections.forEachIndexed { i, s ->
                val name = "s$i.xhtml"
                val body = StringBuilder()
                s.blocks.forEach { b ->
                    if (b.heading) body.append("<h2>").append(esc(b.text)).append("</h2>\n")
                    else body.append("<p>").append(esc(b.text)).append("</p>\n")
                }
                putBytes("OEBPS/$name", xhtml(s.title, lang, body.toString()).toByteArray())
                manifest.append("<item id=\"s$i\" href=\"$name\" media-type=\"application/xhtml+xml\"/>\n")
                spine.append("<itemref idref=\"s$i\"/>\n")
                nav.append("<li><a href=\"$name\">").append(esc(s.title)).append("</a></li>\n")
            }
            if (cover != null) {
                val ext = if (coverType.contains("png")) "png" else "jpg"
                putBytes("OEBPS/cover.$ext", cover)
                manifest.append("<item id=\"cover\" href=\"cover.$ext\" media-type=\"$coverType\" properties=\"cover-image\"/>\n")
            }
            putBytes(
                "OEBPS/nav.xhtml",
                xhtml("Cuprins", lang, "<nav epub:type=\"toc\"><ol>\n$nav</ol></nav>").toByteArray()
            )
            putBytes(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">urn:uuid:${UUID.randomUUID()}</dc:identifier>
<dc:title>${esc(title)}</dc:title>
<dc:creator>${esc(author)}</dc:creator>
<dc:language>${esc(lang)}</dc:language>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
$manifest</manifest>
<spine>
$spine</spine>
</package>""".toByteArray()
            )
        }
    }

    private fun xhtml(title: String, lang: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${esc(lang)}">
<head><meta charset="UTF-8"/><title>${esc(title)}</title></head>
<body>
$body</body>
</html>"""

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> if (c.code >= 0x20 || c == '\n' || c == '\t') append(c)
        }
    }

    /**
     * Splits plain text into paragraphs: blank lines separate paragraphs; single line breaks inside
     * a paragraph are joined (hyphenated line ends are merged). If the text has no blank lines at all,
     * each line is a paragraph.
     */
    fun paragraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u000C', '\n')
        val hasBlank = Regex("\n[ \t]*\n").containsMatchIn(normalized)
        val raw = if (hasBlank) normalized.split(Regex("\n[ \t]*\n+")) else normalized.split('\n')
        return raw.map { p ->
            p.trim().replace(Regex("(\\p{L})-\n(\\p{Ll})"), "$1$2").replace(Regex("\\s*\n\\s*"), " ")
                .replace(Regex("[ \t]{2,}"), " ")
        }.filter { it.isNotBlank() }
    }

    /** Groups paragraphs into sections of roughly [maxChars] characters, starting new ones at chapter-like headings. */
    fun sectionsFromParagraphs(paras: List<String>, fallbackTitle: String, maxChars: Int = 20_000): List<Section> {
        val heading = Regex("^(capitolul|capitol|chapter|partea|part|cartea|book)\\s+[\\w\\dIVXLC]+.*", RegexOption.IGNORE_CASE)
        val out = ArrayList<Section>()
        var cur = ArrayList<Block>()
        var curTitle: String? = null
        var size = 0
        fun flush() {
            if (cur.isNotEmpty()) out.add(Section(curTitle ?: "$fallbackTitle ${out.size + 1}", cur))
            cur = ArrayList(); size = 0; curTitle = null
        }
        for (p in paras) {
            val isHeading = p.length < 80 && heading.matches(p)
            if (isHeading) {
                flush(); curTitle = p
                cur.add(Block(p, heading = true))
                continue
            }
            if (size > maxChars) flush()
            cur.add(Block(p)); size += p.length
        }
        flush()
        return out
    }
}
