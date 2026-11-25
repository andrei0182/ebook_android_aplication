package com.ebook.cititor.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.ebook.cititor.data.Prefs
import com.ebook.cititor.epub.Paragraph
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Natural AI voices from ElevenLabs. Audio is requested paragraph by paragraph (a couple of pieces
 * ahead, so playback is continuous), cached on disk, and played with MediaPlayer. The character
 * timestamps returned by the API drive the word highlight.
 */
class ElevenLabsSpeaker(private val ctx: Context, private val listener: Speaker.Listener) : Speaker {

    private class Piece(val paragraph: Int, val offset: Int, val text: String)

    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val fetchers: ExecutorService = Executors.newFixedThreadPool(2)
    private val waiter: ExecutorService = Executors.newSingleThreadExecutor()
    private var api = ElevenLabsApi(prefs.elevenKey, ctx.cacheDir)
    private var paragraphs: List<Paragraph> = emptyList()
    private var queue: List<Piece> = emptyList()
    private val futures = HashMap<Int, Future<ElevenLabsApi.Audio>>()
    private var pos = 0
    private var generation = 0
    private var player: MediaPlayer? = null
    private var timings: DoubleArray = DoubleArray(0)
    private var lastWord = -1

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val piece = queue.getOrNull(pos) ?: return
            val t = runCatching { p.currentPosition / 1000.0 }.getOrDefault(0.0)
            val ch = lastIndexAtOrBefore(timings, t)
            if (ch >= 0) {
                val w = paragraphs[piece.paragraph].wordAt(piece.offset + ch)
                if (w != lastWord) {
                    lastWord = w
                    listener.onWord(piece.paragraph, w)
                }
            }
            main.postDelayed(this, 50)
        }
    }

    override fun applySettings(): Boolean {
        val key = prefs.elevenKey
        api = ElevenLabsApi(key, ctx.cacheDir)
        player?.let { p ->
            runCatching { if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(prefs.speechRate) }
        }
        return false
    }

    override fun speak(paragraphs: List<Paragraph>, from: Int) {
        stop()
        if (prefs.elevenKey.isBlank()) {
            listener.onSpeakerError("Adaugă cheia API ElevenLabs în ecranul „Voce”."); return
        }
        if (prefs.elevenVoiceId.isBlank()) {
            listener.onSpeakerError("Alege o voce ElevenLabs în ecranul „Voce”."); return
        }
        this.paragraphs = paragraphs
        val pieces = ArrayList<Piece>()
        for (i in from until paragraphs.size) {
            Speaker.split(paragraphs[i].text, MAX_CHARS).forEach { (off, text) ->
                if (text.isNotBlank()) pieces.add(Piece(i, off, text))
            }
        }
        queue = pieces
        pos = 0
        playCurrent(generation)
    }

    private fun fetch(index: Int): Future<ElevenLabsApi.Audio>? {
        val piece = queue.getOrNull(index) ?: return null
        val voice = prefs.elevenVoiceId
        val model = prefs.elevenModel
        val client = api
        return futures.getOrPut(index) { fetchers.submit<ElevenLabsApi.Audio> { client.synthesize(piece.text, voice, model) } }
    }

    private fun playCurrent(gen: Int) {
        if (gen != generation) return
        if (pos >= queue.size) {
            listener.onFinished(); return
        }
        val future = fetch(pos) ?: return
        fetch(pos + 1); fetch(pos + 2)
        val piece = queue[pos]
        listener.onParagraph(piece.paragraph)
        waiter.execute {
            val result = runCatching { future.get() }
            main.post {
                if (gen != generation) return@post
                result.onSuccess { audio -> play(audio, gen) }
                    .onFailure { e ->
                        val msg = (e.cause ?: e).let { c ->
                            if (c is ElevenLabsApi.ApiException) c.message
                            else "Nu s-a putut genera vocea ElevenLabs. Verifică conexiunea la internet."
                        }
                        listener.onSpeakerError(msg ?: "Eroare ElevenLabs")
                    }
            }
        }
    }

    private fun play(audio: ElevenLabsApi.Audio, gen: Int) {
        releasePlayer()
        timings = audio.charStarts
        lastWord = -1
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(audio.file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener {
                if (gen != generation) return@setOnCompletionListener
                futures.remove(pos)
                pos++
                playCurrent(gen)
            }
            mp.start()
            runCatching { mp.playbackParams = mp.playbackParams.setSpeed(prefs.speechRate) }
            player = mp
            main.removeCallbacks(ticker)
            main.post(ticker)
        } catch (e: Exception) {
            mp.release()
            audio.file.delete()
            listener.onSpeakerError("Fișierul audio nu a putut fi redat.")
        }
    }

    private fun releasePlayer() {
        main.removeCallbacks(ticker)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    override fun stop() {
        generation++
        releasePlayer()
        futures.clear()   // running requests finish in the background and land in the cache
    }

    override fun release() {
        stop()
        fetchers.shutdown()
        waiter.shutdown()
    }

    companion object {
        private const val MAX_CHARS = 2500

        /** Index of the last element <= t in an ascending array, or -1. */
        fun lastIndexAtOrBefore(arr: DoubleArray, t: Double): Int {
            var lo = 0
            var hi = arr.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (arr[mid] <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return ans
        }
    }
}
