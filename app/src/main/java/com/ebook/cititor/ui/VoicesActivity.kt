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
            insets
        }

        binding.systemList.layoutManager = LinearLayoutManager(this)
        binding.systemList.adapter = systemAdapter
        binding.elevenList.layoutManager = LinearLayoutManager(this)
        binding.elevenList.adapter = elevenAdapter

        val eleven = prefs.engine == Prefs.ENGINE_ELEVEN
        binding.engine.check(if (eleven) R.id.engine_eleven else R.id.engine_system)
        showPanel(eleven)
        binding.engine.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val useEleven = id == R.id.engine_eleven
            prefs.engine = if (useEleven) Prefs.ENGINE_ELEVEN else Prefs.ENGINE_SYSTEM
            showPanel(useEleven)
            notifyService()
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
        tts?.shutdown()
        preview?.release()
        super.onDestroy()
    }

    private fun showPanel(eleven: Boolean) {
        binding.systemPanel.isVisible = !eleven
        binding.elevenPanel.isVisible = eleven
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

    class Row(val title: String, val subtitle: String, val selected: Boolean, val onClick: () -> Unit)

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
        }
    }
}
