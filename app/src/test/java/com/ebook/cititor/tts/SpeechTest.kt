package com.ebook.cititor.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    @Test
    fun splitKeepsOffsets() {
        val text = "Prima propoziție este aici. A doua propoziție urmează. " .repeat(30)
        val parts = Speaker.split(text, 200)
        assertTrue(parts.size > 1)
        parts.forEach { (off, piece) ->
            assertTrue(piece.length <= 200)
            assertEquals(piece, text.substring(off, off + piece.length))
        }
        assertEquals(text, parts.joinToString("") { it.second })
    }

    @Test
    fun findsCharacterAtTime() {
        val times = doubleArrayOf(0.0, 0.1, 0.25, 0.4, 0.4, 0.9)
        assertEquals(-1, ElevenLabsSpeaker.lastIndexAtOrBefore(times, -0.1))
        assertEquals(0, ElevenLabsSpeaker.lastIndexAtOrBefore(times, 0.05))
        assertEquals(4, ElevenLabsSpeaker.lastIndexAtOrBefore(times, 0.5))
        assertEquals(5, ElevenLabsSpeaker.lastIndexAtOrBefore(times, 3.0))
    }

    @Test
    fun describesApiErrors() {
        val msg = ElevenLabsApi.describeError(401, """{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}""")
        assertTrue(msg.startsWith("Cheia API ElevenLabs nu este validă."))
        assertTrue(msg.endsWith("Invalid API key"))
        assertEquals("Eroare ElevenLabs (500).", ElevenLabsApi.describeError(500, "oops"))
    }
}
