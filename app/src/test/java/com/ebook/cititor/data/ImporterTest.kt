package com.ebook.cititor.data

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImporterTest {
    @Test
    fun extractsArticleText() {
        val html = """<html><body>
            <nav><a href="/">Acasă</a><p>Meniu</p></nav>
            <header><p>Abonează-te</p></header>
            <div class="content"><article>
              <h1>Titlul articolului</h1>
              <p>Primul paragraf al articolului.</p>
              <script>var x = 1;</script>
              <ul><li>Punct unu</li><li><p>Punct doi</p></li></ul>
            </article></div>
            <footer><p>Copyright</p></footer>
          </body></html>"""
        val blocks = Importer.extractArticle(Jsoup.parse(html).body())
        assertEquals(listOf("Titlul articolului", "Primul paragraf al articolului.", "Punct unu", "Punct doi"), blocks.map { it.text })
        assertTrue(blocks[0].heading)
    }

    @Test
    fun picksDenseContainerWithoutArticleTag() {
        val html = """<body><div id="side"><p>scurt</p></div>
            <div id="main"><p>${"Text lung. ".repeat(20)}</p><p>${"Alt text. ".repeat(20)}</p></div></body>"""
        val blocks = Importer.extractArticle(Jsoup.parse(html).body())
        assertEquals(2, blocks.size)
    }

    @Test
    fun decodesLegacyRomanianText() {
        val cp1250 = "Ştefan şi ţara".toByteArray(charset("windows-1250"))
        assertEquals("Ştefan şi ţara", Importer.decodeText(cp1250))
        assertEquals("Ștefan", Importer.decodeText("\uFEFFȘtefan".toByteArray()))
    }
}
