package com.ebook.cititor.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ebook.cititor.R
import com.ebook.cititor.data.Prefs
import com.ebook.cititor.databinding.ActivityVoicesBinding
import com.ebook.cititor.databinding.ItemVoiceBinding
import com.ebook.cititor.tts.ElevenLabsApi
import com.ebook.cititor.tts.PiperEngine
import com.ebook.cititor.tts.PiperVoices
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ebook.cititor.tts.TtsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Choose between the phone's voices and ElevenLabs AI voices, with previews. */
class VoicesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LANGUAGE = "language"
    }

    private lateinit var binding: ActivityVoicesBinding
    private lateinit var prefs: Prefs
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var preview: MediaPlayer? = null
    private val systemAdapter = RowAdapter()
    private val elevenAdapter = RowAdapter()
    private val piperAdapter = RowAdapter()
    private var piperTrack: AudioTrack? = null
    private var elevenVoices: List<ElevenLabsApi.Voice> = emptyList()
    private var systemVoices: List<Voice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoicesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.systemList.updatePadding(bottom = bars.bottom)
            binding.elevenList.updatePadding(bottom = bars.bottom)
            binding.piperList.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.systemList.layoutManager = LinearLayoutManager(this)
        binding.systemList.adapter = systemAdapter
        binding.elevenList.layoutManager = LinearLayoutManager(this)
        binding.elevenList.adapter = elevenAdapter
        binding.piperList.layoutManager = LinearLayoutManager(this)
        binding.piperList.adapter = piperAdapter

        binding.engine.check(
            when (prefs.engine) {
                Prefs.ENGINE_ELEVEN -> R.id.engine_eleven
                Prefs.ENGINE_PIPER -> R.id.engine_piper
                else -> R.id.engine_system
            }
        )
        showPanel(prefs.engine)
        binding.engine.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.engine = when (id) {
                R.id.engine_eleven -> Prefs.ENGINE_ELEVEN
                R.id.engine_piper -> Prefs.ENGINE_PIPER
                else -> Prefs.ENGINE_SYSTEM
            }
            showPanel(prefs.engine)
            notifyService()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PiperVoices.downloads.collect { showPiperVoices(it) }
            }
        }

        binding.installVoices.setOnClickListener {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { Toast.makeText(this, R.string.tts_settings_missing, Toast.LENGTH_SHORT).show() }
        }
        binding.elevenHint.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://elevenlabs.io/app/settings/api-keys"))) }
        }
        binding.elevenKey.setText(prefs.elevenKey)
        binding.elevenLoad.setOnClickListener {
            prefs.elevenKey = binding.elevenKey.text?.toString().orEmpty()
            notifyService()
            loadElevenVoices()
        }
        binding.elevenModel.check(if (prefs.elevenModel == Prefs.MODEL_FAST) R.id.model_fast else R.id.model_quality)
        binding.elevenModel.setOnCheckedChangeListener { _, id ->
            prefs.elevenModel = if (id == R.id.model_fast) Prefs.MODEL_FAST else Prefs.MODEL_QUALITY
            notifyService()
        }

        loadSystemVoices()
        if (prefs.elevenKey.isNotBlank()) loadElevenVoices()
    }

    override fun onDestroy() {
        piperTrack?.release()
        tts?.shutdown()
        preview?.release()
        super.onDestroy()
    }

    private fun showPanel(engine: String) {
        binding.systemPanel.isVisible = engine == Prefs.ENGINE_SYSTEM
        binding.piperPanel.isVisible = engine == Prefs.ENGINE_PIPER
        binding.elevenPanel.isVisible = engine == Prefs.ENGINE_ELEVEN
    }

    // ---------------------------------------------------------------- Piper voices

    private fun showPiperVoices(downloads: Map<String, PiperVoices.Download>) {
        val installed = PiperVoices.installed(this)
        val selected = PiperVoices.choose(installed, prefs.piperVoice, null)?.id
        piperAdapter.submit(PiperVoices.catalog.map { v ->
            val d = downloads[v.id]
            val isInstalled = installed.any { it.id == v.id }
            val subtitle = when {
                d?.error != null -> getString(R.string.piper_failed, d.error)
                d != null -> getString(R.string.piper_downloading, d.progress)
                isInstalled -> getString(R.string.piper_installed)
                else -> getString(R.string.piper_download, v.sizeMb)
            }
            Row("${v.name} · ${v.languageLabel}", subtitle, isInstalled && v.id == selected,
                onLongClick = if (isInstalled) ({ confirmDeletePiper(v) }) else null) {
                when {
                    isInstalled -> selectPiper(v)
                    d == null || d.error != null -> {
                        PiperVoices.clearError(v.id)
                        PiperVoices.download(this, v.id)
                    }
                }
            }
        })
    }

    private fun selectPiper(v: PiperVoices.VoiceInfo) {
        prefs.piperVoice = v.id
        prefs.engine = Prefs.ENGINE_PIPER
        notifyService()
        showPiperVoices(PiperVoices.downloads.value)
        val sample = getString(if (v.language == "ro") R.string.piper_sample_ro else R.string.piper_sample_en)
        lifecycleScope.launch {
            val audio = withContext(Dispatchers.IO) {
                runCatching { PiperEngine.submit(this@VoicesActivity, v.id, sample, prefs.speechRate).get() }
            }
            audio.onSuccess { playSamples(it.samples, it.sampleRate) }
                .onFailure { Toast.makeText(this@VoicesActivity, getString(R.string.piper_error, it.cause?.message ?: it.message), Toast.LENGTH_LONG).show() }
        }
    }

    private fun playSamples(samples: FloatArray, rate: Int) {
        piperTrack?.release()
        if (samples.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        t.play()
        piperTrack = t
    }

    private fun confirmDeletePiper(v: PiperVoices.VoiceInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.piper_delete_title, v.name))
            .setMessage(R.string.piper_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { PiperVoices.delete(this@VoicesActivity, v.id) }
                    if (prefs.piperVoice == v.id) prefs.piperVoice = ""
                    notifyService()
                    showPiperVoices(PiperVoices.downloads.value)
                }
            }
            .show()
    }

    private fun notifyService() = TtsService.command(this, TtsService.ACTION_SETTINGS)

    // ---------------------------------------------------------------- system voices

    private fun loadSystemVoices() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_LONG).show(); return@TextToSpeech
            }
            ttsReady = true
            val lang = intent.getStringExtra(EXTRA_LANGUAGE)?.substringBefore('-')?.takeIf { it != "und" }
                ?: Locale.getDefault().language
            systemVoices = runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
                .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(compareBy<Voice>({ it.locale.language != lang }, { it.locale.displayName }, { -it.quality }, { it.name }))
            showSystemVoices()
        }
    }

    private fun showSystemVoices() {
        val selected = prefs.systemVoice
        val rows = ArrayList<Row>()
        rows.add(Row(getString(R.string.voice_auto), getString(R.string.voice_auto_hint), selected == null) {
            prefs.systemVoice = null; notifyService(); showSystemVoices()
        })
        systemVoices.forEach { v ->
            val quality = when {
                v.quality >= Voice.QUALITY_VERY_HIGH -> getString(R.string.quality_very_high)
                v.quality >= Voice.QUALITY_HIGH -> getString(R.string.quality_high)
                else -> getString(R.string.quality_normal)
            }
            val net = if (v.isNetworkConnectionRequired) " · " + getString(R.string.needs_internet) else ""
            val shortName = v.name.substringAfter("-x-", v.name).substringBefore('#').removeSuffix("-local").removeSuffix("-network")
            rows.add(Row("${v.locale.getDisplayName(Locale.getDefault())} · $shortName", quality + net, v.name == selected) {
                prefs.systemVoice = v.name
                notifyService()
                showSystemVoices()
                previewSystem(v)
            })
        }
        systemAdapter.submit(rows)
    }

    private fun previewSystem(v: Voice) {
        val engine = tts ?: return
        if (!ttsReady) return
        engine.voice = v
        engine.setSpeechRate(prefs.speechRate)
        val sample = if (v.locale.language == "ro") "Bună! Aceasta este vocea care îți va citi cărțile."
        else "Hello! This is the voice that will read your books."
        engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    // ---------------------------------------------------------------- ElevenLabs voices

    private fun loadElevenVoices() {
        val key = prefs.elevenKey
        if (key.isBlank()) {
            Toast.makeText(this, R.string.eleven_need_key, Toast.LENGTH_SHORT).show(); return
        }
        binding.elevenProgress.isVisible = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ElevenLabsApi(key, cacheDir).voices() } }
            binding.elevenProgress.isVisible = false
            result.onSuccess { list ->
                elevenVoices = list
                if (prefs.elevenVoiceId.isBlank() || list.none { it.id == prefs.elevenVoiceId }) {
                    list.firstOrNull()?.let { selectEleven(it, play = false) }
                }
                showElevenVoices()
            }.onFailure {
                val msg = if (it is ElevenLabsApi.ApiException) it.message else getString(R.string.eleven_network_error)
                Toast.makeText(this@VoicesActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showElevenVoices() {
        val selected = prefs.elevenVoiceId
        elevenAdapter.submit(elevenVoices.map { v ->
            Row(v.name, v.description, v.id == selected) { selectEleven(v, play = true) }
        })
    }

    private fun selectEleven(v: ElevenLabsApi.Voice, play: Boolean) {
        prefs.elevenVoiceId = v.id
        prefs.elevenVoiceName = v.name
        notifyService()
        showElevenVoices()
        if (play) v.previewUrl?.let { playPreview(it) }
    }

    private fun playPreview(url: String) {
        preview?.release()
        preview = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            runCatching {
                setDataSource(url)
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }
    }

    // ---------------------------------------------------------------- list

    class Row(
        val title: String,
        val subtitle: String,
        val selected: Boolean,
        val onLongClick: (() -> Unit)? = null,
        val onClick: () -> Unit,
    )

    private class RowAdapter : RecyclerView.Adapter<RowAdapter.H>() {
        private var rows: List<Row> = emptyList()
        class H(val b: ItemVoiceBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<Row>) {
            rows = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            H(ItemVoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: H, position: Int) {
            val r = rows[position]
            h.b.title.text = r.title
            h.b.subtitle.text = r.subtitle
            h.b.subtitle.isVisible = r.subtitle.isNotBlank()
            h.b.check.isVisible = r.selected
            h.itemView.setOnClickListener { r.onClick() }
            h.itemView.setOnLongClickListener(r.onLongClick?.let { f -> android.view.View.OnLongClickListener { f(); true } })
        }
    }
}
