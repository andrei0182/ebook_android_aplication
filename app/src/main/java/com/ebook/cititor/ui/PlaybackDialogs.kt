package com.ebook.cititor.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import com.ebook.cititor.R
import com.ebook.cititor.data.Prefs
import com.ebook.cititor.databinding.DialogSpeedBinding
import com.ebook.cititor.tts.TtsService
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.util.Locale

/** Dialogs shared by the player and the reader. */
object PlaybackDialogs {

    fun formatSpeed(v: Float): String {
        val s = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        return "${s}x"
    }

    fun speed(activity: Activity, onChanged: () -> Unit = {}) {
        val prefs = Prefs(activity)
        val b = DialogSpeedBinding.inflate(LayoutInflater.from(activity))
        b.slider.value = (Math.round(prefs.speechRate * 20) / 20f).coerceIn(0.5f, 3f)
        b.value.text = formatSpeed(b.slider.value)
        fun apply(v: Float) {
            prefs.speechRate = v
            b.value.text = formatSpeed(v)
            TtsService.command(activity, TtsService.ACTION_SETTINGS)
            onChanged()
        }
        b.slider.addOnChangeListener { _, v, fromUser -> if (fromUser) b.value.text = formatSpeed(v) }
        b.slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = apply(slider.value)
        })
        listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { v ->
            val chip = Chip(activity).apply {
                text = formatSpeed(v)
                setOnClickListener { b.slider.value = v; apply(v) }
            }
            b.presets.addView(chip)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.speech_rate)
            .setView(b.root)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    fun sleep(activity: Activity) {
        val s = TtsService.state.value
        val labels = activity.resources.getStringArray(R.array.sleep_options)
        val minutes = intArrayOf(0, TtsService.SLEEP_END_OF_CHAPTER, 10, 15, 30, 45, 60, 90)
        val title = when {
            s.sleepAt == -1L -> activity.getString(R.string.sleep_end_chapter_active)
            s.sleepAt > 0 -> activity.getString(
                R.string.sleep_remaining, ((s.sleepAt - System.currentTimeMillis()) / 60000 + 1).toInt()
            )
            else -> activity.getString(R.string.sleep_timer)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setItems(labels) { _, which ->
                TtsService.command(activity, TtsService.ACTION_SLEEP,
                    Bundle().apply { putInt(TtsService.EXTRA_MINUTES, minutes[which]) })
            }
            .show()
    }
}
