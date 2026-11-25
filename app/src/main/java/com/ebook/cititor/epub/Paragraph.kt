package com.ebook.cititor.epub

/** Text of one readable block and the character offset where each of its words starts. */
class Paragraph(val text: String, val wordStarts: IntArray) {
    /** Index of the word containing character [offset] (clamped to the valid range). */
    fun wordAt(offset: Int): Int {
        if (wordStarts.isEmpty()) return 0
        var lo = 0
        var hi = wordStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (wordStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    override fun toString() = text
}
