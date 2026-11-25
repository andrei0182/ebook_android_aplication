package com.ebook.cititor.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.palette.graphics.Palette
import com.ebook.cititor.R
import com.ebook.cititor.data.Prefs
import com.ebook.cititor.databinding.ActivityPlayerBinding
import com.ebook.cititor.epub.Paragraph
import com.ebook.cititor.tts.PlaybackState
import com.ebook.cititor.tts.PiperVoices
import com.ebook.cititor.tts.TtsService
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Full-screen "now playing" view: cover, live text with the spoken word highlighted, and controls. */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: Prefs
    private var state = PlaybackState()
    private var dragging = false
    private var shownCover: String? = "-"
    private var shownText: Paragraph? = null
    private var shownWord = -2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.play.setOnClickListener { TtsService.command(this, TtsService.ACTION_TOGGLE) }
        binding.rewind.setOnClickListener { TtsService.command(this, TtsService.ACTION_PREV) }
        binding.forward.setOnClickListener { TtsService.command(this, TtsService.ACTION_NEXT) }
        binding.prevChapter.setOnClickListener {
            val s = state
            s.bookId?.let { TtsService.play(this, it, (s.chapter - 1).coerceAtLeast(0), 0) }
        }
        binding.nextChapter.setOnClickListener {
            val s = state
            if (s.chapter < s.chapterCount - 1) s.bookId?.let { TtsService.play(this, it, s.chapter + 1, 0) }
        }
        binding.speed.setOnClickListener { PlaybackDialogs.speed(this) { updateChips() } }
        binding.voice.setOnClickListener { startActivity(Intent(this, VoicesActivity::class.java)) }
        binding.sleep.setOnClickListener { PlaybackDialogs.sleep(this) }
        binding.position.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { dragging = true }
            override fun onStopTrackingTouch(slider: Slider) {
                dragging = false
                val s = state
                s.bookId?.let { TtsService.play(this@PlayerActivity, it, s.chapter, slider.value.toInt()) }
            }
        })
        binding.position.addOnChangeListener { _, v, fromUser ->
            if (fromUser) binding.positionText.text = getString(R.string.paragraph_of, v.toInt() + 1, state.paragraphCount)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TtsService.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateChips()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.open_text).setIcon(R.drawable.ic_book)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            state.bookId?.let {
                startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, it))
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun render(s: PlaybackState) {
        val prev = state
        state = s
        if (s.errorSeq != prev.errorSeq && s.error != null) {
            Snackbar.make(binding.root, s.error, Snackbar.LENGTH_LONG).show()
        }
        if (!s.active) {
            if (prev.active) finish()
            else if (s.bookId == null) {
                binding.bookTitle.text = getString(R.string.nothing_playing)
                binding.play.isEnabled = false
            }
            return
        }
        binding.play.isEnabled = true
        binding.bookTitle.text = s.bookTitle
        binding.chapterTitle.text = s.chapterTitle
        if (s.coverPath != shownCover) {
            shownCover = s.coverPath
            CoverArt.bind(binding.cover, binding.coverTitle, s.bookTitle, s.coverPath)
            tintBackground(s)
        }
        binding.play.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.play.contentDescription = getString(if (s.playing) R.string.pause else R.string.play)
        binding.loading.isVisible = s.loading

        val count = s.paragraphCount
        if (count > 1) {
            binding.position.isEnabled = true
            binding.position.valueTo = (count - 1).toFloat()
            if (!dragging) binding.position.value = s.paragraph.coerceIn(0, count - 1).toFloat()
        } else {
            binding.position.valueTo = 1f
            binding.position.value = 0f
            binding.position.isEnabled = false
        }
        if (!dragging) binding.positionText.text = getString(R.string.paragraph_of, s.paragraph + 1, count.coerceAtLeast(1))
        binding.chapterText.text = getString(R.string.chapter_short, s.chapter + 1, s.chapterCount.coerceAtLeast(1))
        renderText(s.text, s.word)
        if (s.sleepAt != prev.sleepAt || s.engine != prev.engine) updateChips()
    }

    /** Shows the current paragraph; already-read words are dimmed and the spoken word is highlighted. */
    private fun renderText(text: Paragraph?, word: Int) {
        if (text === shownText && word == shownWord) return
        val newParagraph = text !== shownText
        shownText = text
        shownWord = word
        if (text == null) {
            binding.liveText.text = ""; return
        }
        val span = SpannableString(text.text)
        val starts = text.wordStarts
        if (word in starts.indices) {
            val start = starts[word]
            val end = if (word + 1 < starts.size) starts[word + 1] - 1 else text.text.length
            val onSurface = MaterialColors.getColor(binding.liveText, com.google.android.material.R.attr.colorOnSurface)
            val accent = MaterialColors.getColor(binding.liveText, androidx.appcompat.R.attr.colorPrimary)
            if (start > 0) span.setSpan(
                ForegroundColorSpan(ColorUtils.setAlphaComponent(onSurface, 150)), 0, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            span.setSpan(BackgroundColorSpan(ColorUtils.setAlphaComponent(accent, 70)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            binding.liveText.text = span
            binding.liveText.post { scrollToOffset(start) }
        } else {
            binding.liveText.text = span
            if (newParagraph) binding.textScroll.scrollTo(0, 0)
        }
    }

    private fun scrollToOffset(offset: Int) {
        val layout = binding.liveText.layout ?: return
        val line = layout.getLineForOffset(offset)
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val h = binding.textScroll.height
        val scrollY = binding.textScroll.scrollY
        if (top < scrollY || bottom > scrollY + h) {
            binding.textScroll.smoothScrollTo(0, (top - h / 3).coerceAtLeast(0))
        }
    }

    private fun updateChips() {
        binding.speed.text = PlaybackDialogs.formatSpeed(prefs.speechRate)
        binding.voice.text = when (prefs.engine) {
            Prefs.ENGINE_ELEVEN -> prefs.elevenVoiceName.ifBlank { getString(R.string.voice_ai) }
            Prefs.ENGINE_PIPER -> PiperVoices.voice(prefs.piperVoice)?.name
                ?: getString(R.string.voice_piper_short)
            else -> getString(R.string.voice_system_short)
        }
        val s = state
        binding.sleep.text = when {
            s.sleepAt == -1L -> getString(R.string.sleep_chapter_short)
            s.sleepAt > 0 -> getString(R.string.minutes_short, ((s.sleepAt - System.currentTimeMillis()) / 60000 + 1).toInt())
            else -> getString(R.string.sleep_short)
        }
    }

    /** Soft gradient background taken from the cover's colors. */
    private fun tintBackground(s: PlaybackState) {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val surface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        val bmp = CoverArt.load(s.coverPath)
        val base = if (bmp != null) {
            val p = Palette.from(bmp).generate()
            (if (night) p.getDarkMutedColor(0).takeIf { it != 0 } ?: p.getDominantColor(Color.DKGRAY)
            else p.getLightMutedColor(0).takeIf { it != 0 } ?: p.getDominantColor(Color.LTGRAY))
        } else CoverArt.colorFor(s.bookTitle)
        val top = ColorUtils.blendARGB(surface, base, if (night) 0.55f else 0.45f)
        binding.root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, surface))
    }
}
