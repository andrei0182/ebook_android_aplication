package com.ebook.cititor.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookTest {

    private fun buildEpub(files: Map<String, String>): File {
        val f = File.createTempFile("test", ".epub")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
        return f
    }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    @Test
    fun parsesEpub3WithNav() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Amintiri din copilărie</dc:title>
                <dc:creator>Ion Creangă</dc:creator>
                <dc:language>ro</dc:language>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="text/cap%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/cap2.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="img/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val nav = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <nav epub:type="toc"><ol>
                <li><a href="text/cap%201.xhtml">Partea I</a>
                  <ol><li><a href="text/cap2.xhtml#s1">Partea a II-a</a></li></ol></li>
              </ol></nav></body></html>"""
        val c1 = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>1</title></head><body>
              <h1>Partea I</h1>
              <p>Stau câteodată și-mi aduc aminte&nbsp;ce vremi și ce oameni mai erau.</p>
              <blockquote><p>Citat imbricat.</p></blockquote>
              <p>   </p>
              <div><p>Ultimul paragraf.</p></div>
            </body></html>"""
        val c2 = "<html><body><div>Text fără paragrafe <span>și cu span</span></div></body></html>"
        val file = buildEpub(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/text/cap 1.xhtml" to c1,
                "OEBPS/text/cap2.xhtml" to c2,
                "OEBPS/img/cover.jpg" to "x",
            )
        )
        EpubBook.open(file).use { book ->
            assertEquals("Amintiri din copilărie", book.title)
            assertEquals("Ion Creangă", book.author)
            assertEquals("ro", book.language)
            assertEquals(listOf("OEBPS/text/cap 1.xhtml", "OEBPS/text/cap2.xhtml"), book.spine.map { it.path })
            assertEquals("OEBPS/img/cover.jpg", book.coverPath)
            assertEquals(2, book.toc.size)
            assertEquals(EpubBook.TocEntry("Partea I", 0, 0), book.toc[0])
            assertEquals(EpubBook.TocEntry("Partea a II-a", 1, 1), book.toc[1])

            val ch = book.loadChapter(0, "<style id=\"__rs\"></style>")
            assertEquals(4, ch.paragraphs.size)
            assertEquals("Partea I", ch.paragraphs[0])
            assertTrue(ch.paragraphs[1].startsWith("Stau câteodată"))
            assertEquals("Citat imbricat.", ch.paragraphs[2])
            assertEquals("Ultimul paragraf.", ch.paragraphs[3])
            assertTrue(ch.html.contains("data-tts=\"3\""))
            assertTrue(ch.html.contains("__rs"))

            val ch2 = book.loadChapter(1)
            assertEquals(listOf("Text fără paragrafe și cu span"), ch2.paragraphs)
        }
    }

    @Test
    fun parsesEpub2WithNcx() {
        val opf = """<?xml version="1.0"?>
            <opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="2.0">
              <opf:metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Carte veche</dc:title>
                <opf:meta name="cover" content="cv"/></opf:metadata>
              <opf:manifest>
                <opf:item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <opf:item id="a" href="a.html" media-type="application/xhtml+xml"/>
                <opf:item id="cv" href="../cover.png" media-type="image/png"/>
              </opf:manifest>
              <opf:spine toc="ncx"><opf:itemref idref="a"/></opf:spine>
            </opf:package>"""
        val ncx = """<ncx><navMap><navPoint id="n1"><navLabel><text>Început</text></navLabel>
            <content src="a.html#x"/></navPoint></navMap></ncx>"""
        val file = buildEpub(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/toc.ncx" to ncx,
                "OEBPS/a.html" to "<p>Unu</p><p>Doi</p>",
            )
        )
        EpubBook.open(file).use { book ->
            assertEquals("Carte veche", book.title)
            assertEquals("cover.png", book.coverPath)
            assertEquals(listOf(EpubBook.TocEntry("Început", 0, 0)), book.toc)
            assertEquals("Început", book.chapterTitle(0))
            assertEquals(listOf("Unu", "Doi"), book.loadChapter(0).paragraphs)
        }
    }

    @Test
    fun resolvesPaths() {
        assertEquals("OEBPS/img/a.png", EpubBook.resolvePath("OEBPS/text", "../img/a.png"))
        assertEquals("a b.html", EpubBook.resolvePath("", "a%20b.html#frag"))
        assertEquals("ț.html", EpubBook.resolvePath("", "%C8%9B.html"))
    }
}
