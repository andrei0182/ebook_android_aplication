package com.ebook.cititor.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ebook.cititor.data.Prefs
import com.ebook.cititor.epub.Paragraph
import java.util.Locale

/** Android's built-in text-to-speech (free, works offline with downloaded voices). */
class SystemSpeaker(ctx: Context, private val listener: Speaker.Listener) : Speaker {

    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var generation = 0
    private var paragraphs: List<Paragraph> = emptyList()
    private var language: String? = null
    private var lastWord = -1

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        main.post { onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            listener.onSpeakerError("Motorul Text-to-Speech nu este disponibil. Instalează „Speech Services by Google” din Magazin Play.")
            return
        }
        ready = true
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        tts.setOnUtteranceProgressListener(progress)
        applyVoice()
        pending?.invoke()
        pending = null
    }

    override fun setLanguage(tag: String?) {
        language = tag
        if (ready) applyVoice()
    }

    private fun applyVoice() {
        val chosen = prefs.systemVoice?.let { name ->
            runCatching { tts.voices?.firstOrNull { it.name == name } }.getOrNull()
        }
        if (chosen != null) {
            tts.voice = chosen
            return
        }
        val tag = language
        if (!tag.isNullOrBlank() && tag != "und") {
            val locale = Locale.forLanguageTag(tag)
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        } else {
            tts.language = Locale.getDefault()
        }
    }

    override fun applySettings(): Boolean {
        if (ready) applyVoice()
        return true
    }

    override fun speak(paragraphs: List<Paragraph>, from: Int) {
        if (!ready) {
            pending = { speak(paragraphs, from) }; return
        }
        this.paragraphs = paragraphs
        tts.stop()
        val gen = ++generation
        lastWord = -1
        tts.setSpeechRate(prefs.speechRate)
        tts.setPitch(prefs.speechPitch)
        val maxLen = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        var first = true
        for (i in from until paragraphs.size) {
            val chunks = Speaker.split(paragraphs[i].text, maxLen)
            chunks.forEachIndexed { k, (offset, chunk) ->
                val last = i == paragraphs.size - 1 && k == chunks.size - 1
                tts.speak(chunk, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null,
                    "$gen:$i:$offset:${if (last) 1 else 0}")
                first = false
            }
            tts.playSilentUtterance(250, TextToSpeech.QUEUE_ADD, "$gen:s:0:0")
        }
        if (first) main.post { if (gen == generation) listener.onFinished() }
    }

    override fun stop() {
        generation++
        pending = null
        if (ready) tts.stop()
    }

    override fun release() {
        stop()
        tts.shutdown()
    }

    private class Id(val gen: Int, val paragraph: Int, val offset: Int, val last: Boolean)

    private fun parse(id: String?): Id? {
        val p = id?.split(':') ?: return null
        if (p.size < 4 || p[1] == "s") return null
        return Id(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null,
            p[2].toIntOrNull() ?: 0, p[3] == "1")
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val id = parse(utteranceId) ?: return
            main.post {
                if (id.gen != generation) return@post
                listener.onParagraph(id.paragraph)
                if (id.offset == 0) {
                    lastWord = 0
                    listener.onWord(id.paragraph, 0)
                }
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val id = parse(utteranceId) ?: return
            main.post {
                if (id.gen != generation) return@post
                val para = paragraphs.getOrNull(id.paragraph) ?: return@post
                val w = para.wordAt(id.offset + start)
                if (w != lastWord) {
                    lastWord = w
                    listener.onWord(id.paragraph, w)
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            val id = parse(utteranceId) ?: return
            if (!id.last) return
            main.post { if (id.gen == generation) listener.onFinished() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onDone(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = onDone(utteranceId)
    }
}
