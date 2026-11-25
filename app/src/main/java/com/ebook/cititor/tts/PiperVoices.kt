package com.ebook.cititor.tts

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Free, offline neural voices (Piper models, run with sherpa-onnx). Voices are downloaded once
 * on demand; the espeak-ng pronunciation data is shared by all voices.
 */
object PiperVoices {

    data class VoiceInfo(val id: String, val name: String, val language: String, val languageLabel: String, val sizeMb: Int)

    data class Download(val progress: Int, val error: String? = null)

    val catalog = listOf(
        VoiceInfo("ro_RO-mihai-medium", "Mihai", "ro", "Română", 67),
        VoiceInfo("en_US-lessac-medium", "Lessac", "en", "Engleză (SUA)", 65),
        VoiceInfo("en_US-amy-medium", "Amy", "en", "Engleză (SUA)", 65),
        VoiceInfo("en_US-ryan-medium", "Ryan", "en", "Engleză (SUA)", 65),
        VoiceInfo("en_GB-alba-medium", "Alba", "en", "Engleză (UK)", 65),
    )

    private const val BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-"
    private const val ESPEAK = "espeak-ng-data"
    private const val COMPLETE = ".complete"

    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloads: StateFlow<Map<String, Download>> = _downloads

    fun root(ctx: Context) = File(ctx.filesDir, "piper")

    class Paths(val model: String, val tokens: String, val dataDir: String)

    fun paths(ctx: Context, id: String): Paths? = paths(root(ctx), id)

    fun paths(root: File, id: String): Paths? {
        val dir = File(root, id)
        val espeak = File(root, ESPEAK)
        val model = dir.listFiles { f -> f.extension == "onnx" }?.firstOrNull() ?: return null
        val tokens = File(dir, "tokens.txt")
        if (!tokens.exists() || !File(dir, COMPLETE).exists() || !File(espeak, COMPLETE).exists()) return null
        return Paths(model.absolutePath, tokens.absolutePath, espeak.absolutePath)
    }

    fun isInstalled(ctx: Context, id: String) = paths(ctx, id) != null

    fun installed(ctx: Context): List<VoiceInfo> = catalog.filter { isInstalled(ctx, it.id) }

    /** Voice to use: the chosen one if it fits the book's language, else an installed voice for that language. */
    fun choose(installed: List<VoiceInfo>, preferred: String?, language: String?): VoiceInfo? {
        if (installed.isEmpty()) return null
        val pref = installed.firstOrNull { it.id == preferred }
        val lang = language?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotBlank() && it != "und" }
            ?: return pref ?: installed.first()
        if (pref?.language == lang) return pref
        return installed.firstOrNull { it.language == lang } ?: pref ?: installed.first()
    }

    fun delete(ctx: Context, id: String) {
        File(root(ctx), id).deleteRecursively()
        PiperEngine.unload()
        if (installed(ctx).isEmpty()) File(root(ctx), ESPEAK).deleteRecursively()
    }

    /** Starts downloading [id] in the background; progress is published in [downloads]. */
    fun download(ctx: Context, id: String) {
        val current = _downloads.value[id]
        if (current != null && current.error == null) return
        val app = ctx.applicationContext
        update(id, Download(0))
        Thread({
            try {
                downloadBlocking(app, id) { pct -> update(id, Download(pct)) }
                remove(id)
            } catch (e: Exception) {
                update(id, Download(0, e.message ?: e.javaClass.simpleName))
            }
        }, "piper-download").start()
    }

    fun clearError(id: String) {
        if (_downloads.value[id]?.error != null) remove(id)
    }

    @Synchronized
    private fun update(id: String, d: Download) {
        _downloads.value = _downloads.value + (id to d)
    }

    @Synchronized
    private fun remove(id: String) {
        _downloads.value = _downloads.value - id
    }

    private fun downloadBlocking(ctx: Context, id: String, progress: (Int) -> Unit) {
        val root = root(ctx).apply { mkdirs() }
        var url = URL("$BASE_URL$id.tar.bz2")
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "CititorEpub")
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                redirects++
                continue
            }
            if (code != 200) throw java.io.IOException("Descărcarea a eșuat (HTTP $code)")
            break
        }
        try {
            val total = conn.contentLengthLong
            var read = 0L
            var lastPct = -1
            val counting = object : FilterInputStream(conn.inputStream) {
                override fun read(): Int = super.read().also { if (it >= 0) tick(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) tick(it) }
                private fun tick(n: Int) {
                    read += n
                    if (total > 0) {
                        val pct = (read * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
            }
            extract(counting, root, id)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Unpacks a `vits-piper-<id>.tar.bz2` archive: model files go to `root/<id>`, the espeak-ng
     * data to `root/espeak-ng-data` (only once, shared). Files are only marked complete at the end.
     */
    fun extract(input: InputStream, root: File, id: String) {
        val espeakDir = File(root, ESPEAK)
        val haveEspeak = File(espeakDir, COMPLETE).exists()
        val tmp = File(root, "$id.part")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val rootPath = root.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input, 64 * 1024))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val rel = entry.name.trimStart('/').substringAfter('/', "")
                if (rel.isEmpty()) continue
                val target = if (rel == ESPEAK || rel.startsWith("$ESPEAK/")) {
                    if (haveEspeak) continue
                    File(root, rel)
                } else File(tmp, rel)
                require(target.canonicalPath.startsWith(rootPath)) { "Arhivă invalidă" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else if (entry.isFile) {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { tar.copyTo(it) }
                }
            }
        }
        require(tmp.listFiles { f -> f.extension == "onnx" }?.isNotEmpty() == true && File(tmp, "tokens.txt").exists()) {
            "Arhiva vocii este incompletă"
        }
        if (!haveEspeak) {
            require(espeakDir.isDirectory) { "Lipsesc datele de pronunție" }
            File(espeakDir, COMPLETE).writeText("ok")
        }
        File(tmp, COMPLETE).writeText("ok")
        val dest = File(root, id)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) {
            tmp.copyRecursively(dest, overwrite = true); tmp.deleteRecursively()
        }
    }
}
