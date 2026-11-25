package com.ebook.cititor.tts

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/** Minimal client for the ElevenLabs text-to-speech REST API. */
class ElevenLabsApi(private val apiKey: String, private val cacheDir: File) {

    data class Voice(val id: String, val name: String, val description: String, val previewUrl: String?)

    /** Synthesized audio plus the start time (seconds) of each character of the input text. */
    class Audio(val file: File, val charStarts: DoubleArray)

    class ApiException(message: String) : IOException(message)

    fun voices(): List<Voice> {
        val json = request("GET", "/v1/voices", null)
        val arr = JSONObject(json).optJSONArray("voices") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val v = arr.getJSONObject(i)
            val labels = v.optJSONObject("labels")
            val desc = labels?.let { l ->
                l.keys().asSequence().mapNotNull { k -> l.optString(k).takeIf { it.isNotBlank() } }.joinToString(" · ")
            }.orEmpty()
            Voice(v.getString("voice_id"), v.optString("name"), desc, v.optString("preview_url").takeIf { it.isNotBlank() })
        }.sortedBy { it.name.lowercase() }
    }

    /** Returns cached audio for this text/voice/model if available; otherwise calls the API. */
    fun synthesize(text: String, voiceId: String, model: String): Audio {
        val dir = File(cacheDir, "eleven").apply { mkdirs() }
        val key = sha1("$voiceId|$model|$text")
        val audioFile = File(dir, "$key.mp3")
        val timingFile = File(dir, "$key.json")
        if (audioFile.exists() && timingFile.exists()) {
            audioFile.setLastModified(System.currentTimeMillis())
            return Audio(audioFile, parseTimes(JSONArray(timingFile.readText())))
        }
        val body = JSONObject().put("text", text).put("model_id", model)
        val json = JSONObject(
            request("POST", "/v1/text-to-speech/$voiceId/with-timestamps?output_format=mp3_44100_128", body.toString())
        )
        val audio = Base64.getDecoder().decode(json.getString("audio_base64"))
        val starts = json.optJSONObject("alignment")?.optJSONArray("character_start_times_seconds") ?: JSONArray()
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(audio)
        timingFile.writeText(starts.toString())
        tmp.renameTo(audioFile)
        trimCache(dir)
        return Audio(audioFile, parseTimes(starts))
    }

    private fun parseTimes(arr: JSONArray) = DoubleArray(arr.length()) { arr.optDouble(it, 0.0) }

    private fun request(method: String, path: String, body: String?): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("xi-api-key", apiKey)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw ApiException(describeError(code, err))
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://api.elevenlabs.io"
        private const val CACHE_LIMIT = 300L * 1024 * 1024

        fun describeError(code: Int, body: String): String {
            val detail = runCatching {
                val d = JSONObject(body).opt("detail")
                when (d) {
                    is JSONObject -> d.optString("message").ifBlank { d.optString("status") }
                    is String -> d
                    else -> ""
                }
            }.getOrDefault("")
            val base = when (code) {
                401 -> "Cheia API ElevenLabs nu este validă."
                402 -> "Contul ElevenLabs nu are suficiente credite."
                429 -> "Limita ElevenLabs a fost atinsă (prea multe cereri sau credite epuizate)."
                404 -> "Vocea ElevenLabs nu a fost găsită. Alege altă voce."
                else -> "Eroare ElevenLabs ($code)."
            }
            return if (detail.isNotBlank()) "$base $detail" else base
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun trimCache(dir: File) {
            val files = dir.listFiles()?.filter { it.extension == "mp3" } ?: return
            var total = files.sumOf { it.length() }
            if (total <= CACHE_LIMIT) return
            for (f in files.sortedBy { it.lastModified() }) {
                total -= f.length()
                f.delete()
                File(dir, f.nameWithoutExtension + ".json").delete()
                if (total <= CACHE_LIMIT * 3 / 4) break
            }
        }
    }
}
