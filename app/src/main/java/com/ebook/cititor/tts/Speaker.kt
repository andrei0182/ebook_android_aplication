package com.ebook.cititor.tts

import com.ebook.cititor.epub.Paragraph

/** A voice engine that reads a list of paragraphs aloud and reports progress word by word. */
interface Speaker {
    /** All callbacks are delivered on the main thread. */
    interface Listener {
        fun onParagraph(index: Int)
        fun onWord(index: Int, word: Int)
        fun onFinished()
        fun onSpeakerError(message: String)
    }

    /** Speaks [paragraphs] starting at [from], replacing anything currently queued. */
    fun speak(paragraphs: List<Paragraph>, from: Int)
    fun stop()
    fun release()
    fun setLanguage(tag: String?) {}

    /** Applies speed/pitch/voice changes. Returns true if speech must be restarted to take effect. */
    fun applySettings(): Boolean

    companion object {
        /** Splits long text at sentence boundaries; returns (offset, piece) pairs covering the text. */
        fun split(text: String, maxLen: Int): List<Pair<Int, String>> {
            if (text.length <= maxLen) return listOf(0 to text)
            val out = ArrayList<Pair<Int, String>>()
            var start = 0
            while (text.length - start > maxLen) {
                val window = text.substring(start, start + maxLen)
                var cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "))
                if (cut < maxLen / 3) cut = window.lastIndexOf(' ')
                if (cut <= 0) cut = maxLen - 1
                out.add(start to text.substring(start, start + cut + 1))
                start += cut + 1
            }
            if (start < text.length) out.add(start to text.substring(start))
            return out
        }
    }
}
