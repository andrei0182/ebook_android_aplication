package com.ebook.cititor.ui

import android.app.Activity
import android.content.Intent
import androidx.core.view.isVisible
import com.ebook.cititor.R
import com.ebook.cititor.databinding.ViewMiniPlayerBinding
import com.ebook.cititor.tts.PlaybackState
import com.ebook.cititor.tts.TtsService

/** Compact "now playing" bar shown at the bottom of the library. */
class MiniPlayer(private val activity: Activity, private val b: ViewMiniPlayerBinding) {
    private var coverShown: String? = "-"

    init {
        b.miniCard.setOnClickListener { activity.startActivity(Intent(activity, PlayerActivity::class.java)) }
        b.miniPlay.setOnClickListener { TtsService.command(activity, TtsService.ACTION_TOGGLE) }
        b.miniClose.setOnClickListener { TtsService.command(activity, TtsService.ACTION_STOP) }
    }

    fun render(s: PlaybackState) {
        b.miniCard.isVisible = s.active && s.bookId != null
        if (!s.active) return
        b.miniTitle.text = s.bookTitle
        b.miniSubtitle.text = s.chapterTitle
        b.miniPlay.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.miniPlay.contentDescription = activity.getString(if (s.playing) R.string.pause else R.string.play)
        val total = s.chapterCount.coerceAtLeast(1)
        val within = if (s.paragraphCount > 0) s.paragraph.toFloat() / s.paragraphCount else 0f
        b.miniProgress.progress = (((s.chapter + within) / total) * 100).toInt().coerceIn(0, 100)
        if (s.coverPath != coverShown) {
            coverShown = s.coverPath
            CoverArt.bind(b.miniCover, null, s.bookTitle, s.coverPath)
        }
    }
}
