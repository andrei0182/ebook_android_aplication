package com.ebook.cititor.tts

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class PiperTest {

    private fun archive(id: String, files: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("vits-piper-$id/")); tar.closeArchiveEntry()
            files.forEach { (name, content) ->
                val data = content.toByteArray()
                val e = TarArchiveEntry("vits-piper-$id/$name").apply { size = data.size.toLong() }
                tar.putArchiveEntry(e); tar.write(data); tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val files = mapOf(
        "ro_RO-x.onnx" to "model", "tokens.txt" to "tokens", "MODEL_CARD" to "card",
        "espeak-ng-data/phontab" to "ph", "espeak-ng-data/voices/ro" to "v",
    )

    @Test
    fun extractsAndSharesEspeakData() {
        val root = Files.createTempDirectory("piper").toFile()
        PiperVoices.extract(ByteArrayInputStream(archive("a", files)), root, "a")
        val p = PiperVoices.paths(root, "a")
        assertNotNull(p)
        assertTrue(p!!.model.endsWith("a/ro_RO-x.onnx"))
        assertEquals("v", File(root, "espeak-ng-data/voices/ro").readText())
        assertFalse(File(root, "a/espeak-ng-data").exists())

        // Second voice: espeak data is not extracted again.
        File(root, "espeak-ng-data/voices/ro").writeText("kept")
        PiperVoices.extract(ByteArrayInputStream(archive("b", files)), root, "b")
        assertNotNull(PiperVoices.paths(root, "b"))
        assertEquals("kept", File(root, "espeak-ng-data/voices/ro").readText())
        root.deleteRecursively()
    }

    @Test
    fun rejectsIncompleteOrMaliciousArchives() {
        val root = Files.createTempDirectory("piper").toFile()
        val noModel = files - "ro_RO-x.onnx"
        assertTrue(runCatching { PiperVoices.extract(ByteArrayInputStream(archive("c", noModel)), root, "c") }.isFailure)
        assertNull(PiperVoices.paths(root, "c"))
        val evil = files + ("../../evil.txt" to "x")
        assertTrue(runCatching { PiperVoices.extract(ByteArrayInputStream(archive("d", evil)), root, "d") }.isFailure)
        assertFalse(File(root.parentFile, "evil.txt").exists())
        root.deleteRecursively()
    }

    /** Runs against a real downloaded model when PIPER_TEST_ARCHIVE points to one. */
    @Test
    fun extractsRealArchive() {
        val path = System.getenv("PIPER_TEST_ARCHIVE")
        assumeTrue(path != null && File(path).exists())
        val root = Files.createTempDirectory("piper").toFile()
        File(path!!).inputStream().use { PiperVoices.extract(it, root, "ro_RO-mihai-medium") }
        val p = PiperVoices.paths(root, "ro_RO-mihai-medium")!!
        assertTrue(File(p.model).length() > 10_000_000)
        assertTrue(File(p.dataDir, "phontab").exists())
        root.deleteRecursively()
    }

    @Test
    fun choosesVoiceByBookLanguage() {
        val ro = PiperVoices.catalog.first { it.language == "ro" }
        val amy = PiperVoices.catalog.first { it.id == "en_US-amy-medium" }
        val alba = PiperVoices.catalog.first { it.id == "en_GB-alba-medium" }
        val installed = listOf(ro, amy, alba)
        assertNull(PiperVoices.choose(emptyList(), ro.id, "ro"))
        assertEquals(ro, PiperVoices.choose(installed, alba.id, "ro-RO"))
        assertEquals(alba, PiperVoices.choose(installed, alba.id, "en"))
        assertEquals(amy, PiperVoices.choose(installed, ro.id, "en_US"))
        assertEquals(alba, PiperVoices.choose(installed, alba.id, "und"))
        assertEquals(ro, PiperVoices.choose(installed, "", null))
        assertEquals(ro, PiperVoices.choose(installed, ro.id, "de"))
    }

    @Test
    fun estimatesWordPositions() {
        val starts = PiperSpeaker.wordStartFractions("Ana are mere. Bine!")
        assertEquals(4, starts.size)
        assertEquals(0.0, starts[0], 1e-9)
        assertTrue(starts.toList().zipWithNext().all { (a, b) -> b > a })
        // The pause after "mere." pushes "Bine!" later than its share of letters alone.
        assertTrue(starts[3] > 0.6)
        assertEquals(0, PiperSpeaker.wordAtFraction(starts, 0.0))
        assertEquals(3, PiperSpeaker.wordAtFraction(starts, 0.99))
        assertArrayEquals(DoubleArray(0), PiperSpeaker.wordStartFractions("  "), 0.0)
    }

    @Test
    fun catalogIsConsistent() {
        val ids = PiperVoices.catalog.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // Voices sharing a model differ by speaker id, and single-speaker voices use speaker 0.
        PiperVoices.catalog.groupBy { it.model }.forEach { (model, voices) ->
            assertEquals(voices.size, voices.map { it.speaker }.toSet().size)
            if (voices.size == 1 && !voices[0].id.contains('#')) assertEquals(0, voices[0].speaker)
            assertTrue(model.matches(Regex("[a-z]{2}_[A-Z]{2}-[a-z_]+-(low|medium|high)")))
        }
        val vctk = PiperVoices.catalog.filter { it.model == "en_GB-vctk-medium" }
        assertEquals(setOf(76, 104, 10), vctk.map { it.speaker }.toSet())
        assertEquals("Deep", PiperVoices.voice("en_GB-vctk-medium#76")?.name)
    }

    @Test
    fun choosesBritishMaleVoiceForEnglishBooks() {
        val ro = PiperVoices.voice("ro_RO-mihai-medium")!!
        val deep = PiperVoices.voice("en_GB-vctk-medium#76")!!
        val calm = PiperVoices.voice("en_GB-vctk-medium#10")!!
        val installed = listOf(ro, deep, calm)
        assertEquals(calm, PiperVoices.choose(installed, calm.id, "en-GB"))
        assertEquals(deep, PiperVoices.choose(installed, ro.id, "en"))
        assertEquals(ro, PiperVoices.choose(installed, calm.id, "ro"))
    }
}
