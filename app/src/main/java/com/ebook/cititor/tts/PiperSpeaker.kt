package com.ebook.cititor.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.ebook.cititor.R
import com.ebook.cititor.data.Prefs
import com.ebook.cititor.epub.Paragraph
import com.k2fsa.sherpa.onnx.GeneratedAudio
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * Free offline neural voice (Piper). Text is synthesized a few pieces ahead and streamed into one
 * AudioTrack, so playback is gapless. Piper gives no word timestamps, so the current word is
 * estimated from the playback position, weighting words by length and punctuation pauses.
 */
class PiperSpeaker(ctx: Context, private val listener: Speaker.Listener) : Speaker {

    private class Piece(val paragraph: Int, val offset: Int, val text: String)

    /** A piece already written to the AudioTrack, starting at [startFrame]. */
    private class Placed(val piece: Int, val startFrame: Long, val frames: Int, val wordStarts: DoubleArray)

    private val app = ctx.applicationContext
    private val prefs = Prefs(app)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null
    private var language: String? = null
    private var paragraphs: List<Paragraph> = emptyList()
    private var pieces: List<Piece> = emptyList()
    private val placed = CopyOnWriteArrayList<Placed>()
    private var lastParagraph = -1
    private var lastWord = -1

    override fun setLanguage(tag: String?) {
        language = tag
    }

    override fun applySettings(): Boolean = true

    private fun voiceId(): String? =
        PiperVoices.choose(PiperVoices.installed(app), prefs.piperVoice, language)?.id

    override fun speak(paragraphs: List<Paragraph>, from: Int) {
        stop()
        val voice = voiceId()
        if (voice == null) {
            listener.onSpeakerError(app.getString(R.string.piper_need_voice)); return
        }
        this.paragraphs = paragraphs
        val list = ArrayList<Piece>()
        for (i in from until paragraphs.size) {
            Speaker.split(paragraphs[i].text, MAX_CHARS).forEach { (off, text) ->
                if (text.isNotBlank()) list.add(Piece(i, off, text))
            }
        }
        pieces = list
        placed.clear()
        lastParagraph = -1
        lastWord = -1
        val gen = generation
        if (list.isEmpty()) {
            main.post { if (gen == generation) listener.onFinished() }
            return
        }
        val speed = prefs.speechRate
        Thread({ playLoop(gen, voice, speed, list) }, "piper-play").start()
        main.post(ticker)
    }

    private fun playLoop(gen: Int, voice: String, speed: Float, list: List<Piece>) {
        val futures = HashMap<Int, Future<GeneratedAudio>>()
        fun fetch(i: Int) {
            if (i < list.size && i !in futures) futures[i] = PiperEngine.submit(app, voice, list[i].text, speed)
        }
        var at: AudioTrack? = null
        try {
            var written = 0L
            for (i in list.indices) {
                if (gen != generation) return
                fetch(i); fetch(i + 1); fetch(i + 2)
                val audio = futures.remove(i)!!.get()
                if (gen != generation) return
                val samples = audio.samples
                if (samples.isEmpty()) continue
                if (at == null) {
                    at = buildTrack(audio.sampleRate)
                    track = at
                    at.play()
                }
                placed.add(Placed(i, written, samples.size, wordStartFractions(list[i].text)))
                var off = 0
                while (off < samples.size) {
                    if (gen != generation) return
                    val n = at.write(samples, off, minOf(4096, samples.size - off), AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) throw IllegalStateException("AudioTrack error $n")
                    if (n == 0) Thread.sleep(15) else off += n
                }
                written += samples.size
            }
            val t = at
            if (t != null) {
                t.stop() // plays out what is buffered
                val rate = t.sampleRate.coerceAtLeast(1)
                val deadline = System.currentTimeMillis() + 3000 + (written - head(t)).coerceAtLeast(0) * 1000 / rate
                while (gen == generation && head(t) < written && System.currentTimeMillis() < deadline) Thread.sleep(40)
            }
            main.post {
                if (gen == generation) {
                    main.removeCallbacks(ticker)
                    listener.onFinished()
                }
            }
        } catch (e: InterruptedException) {
            // stopped
        } catch (e: Exception) {
            val cause = (e as? ExecutionException)?.cause ?: e
            main.post {
                if (gen == generation) listener.onSpeakerError(app.getString(R.string.piper_error, cause.message ?: cause.javaClass.simpleName))
            }
        } finally {
            futures.values.forEach { it.cancel(false) }
            at?.let { t ->
                runCatching { t.pause(); t.flush() }
                if (track === t) track = null
                t.release()
            }
        }
    }

    private fun head(t: AudioTrack): Long = runCatching { t.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(0L)

    private fun buildTrack(rate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, rate * 4 / 2)) // ~0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val t = track
            if (t != null) {
                val pos = head(t)
                val p = placed.lastOrNull { it.startFrame <= pos }
                if (p != null && p.piece < pieces.size) {
                    val piece = pieces[p.piece]
                    val para = paragraphs.getOrNull(piece.paragraph)
                    if (para != null) {
                        val frac = ((pos - p.startFrame).toDouble() / p.frames).coerceIn(0.0, 1.0)
                        val word = para.wordAt(piece.offset) + wordAtFraction(p.wordStarts, frac)
                        if (piece.paragraph != lastParagraph) {
                            lastParagraph = piece.paragraph
                            listener.onParagraph(piece.paragraph)
                        }
                        if (word != lastWord) {
                            lastWord = word
                            listener.onWord(piece.paragraph, word)
                        }
                    }
                }
            }
            main.postDelayed(this, 50)
        }
    }

    override fun stop() {
        generation++
        main.removeCallbacks(ticker)
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    override fun release() {
        stop()
        PiperEngine.unload()
    }

    companion object {
        private const val MAX_CHARS = 400

        /**
         * Estimated start of each word as a fraction of the piece's audio. Words are weighted by
         * length plus a pause after punctuation; leading silence is ignored.
         */
        fun wordStartFractions(text: String): DoubleArray {
            val words = text.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return DoubleArray(0)
            val weights = words.map { w ->
                val pause = when (w.last()) {
                    '.', '!', '?', '…' -> 8.0
                    ',', ';', ':', '—', '–' -> 4.0
                    else -> 0.0
                }
                w.length + 1.0 + pause
            }
            val total = weights.sum()
            val out = DoubleArray(words.size)
            var acc = 0.0
            for (i in words.indices) {
                out[i] = acc / total
                acc += weights[i]
            }
            return out
        }

        fun wordAtFraction(starts: DoubleArray, frac: Double): Int {
            var ans = 0
            for (i in starts.indices) if (starts[i] <= frac) ans = i else break
            return ans
        }
    }
}
