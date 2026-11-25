package com.ebook.cititor.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Holds the single loaded Piper model. All synthesis runs on one background thread, because the
 * native engine is not thread-safe and keeping one model in memory is enough.
 */
object PiperEngine {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "piper-synth").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private var tts: OfflineTts? = null
    private var loadedId: String? = null

    fun submit(ctx: Context, voice: PiperVoices.VoiceInfo, text: String, speed: Float): Future<GeneratedAudio> {
        val app = ctx.applicationContext
        return executor.submit<GeneratedAudio> {
            engine(app, voice.model).generate(text, voice.speaker, speed.coerceIn(0.5f, 3f))
        }
    }

    fun unload() {
        executor.execute {
            tts?.release()
            tts = null
            loadedId = null
        }
    }

    private fun engine(ctx: Context, id: String): OfflineTts {
        tts?.let { if (loadedId == id) return it }
        tts?.release()
        tts = null
        loadedId = null
        val p = PiperVoices.paths(ctx, id) ?: throw IllegalStateException("Vocea nu este descărcată")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = p.model, tokens = p.tokens, dataDir = p.dataDir),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        return OfflineTts(config = config).also {
            tts = it
            loadedId = id
        }
    }
}
