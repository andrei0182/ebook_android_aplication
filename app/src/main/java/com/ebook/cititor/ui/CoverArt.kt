package com.ebook.cititor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.AttributeSet
import android.util.LruCache
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible

/** ImageView that keeps a book-cover aspect ratio (2:3). */
class CoverImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : AppCompatImageView(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        setMeasuredDimension(w, w * 3 / 2)
    }
}

object CoverArt {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val palette = intArrayOf(
        0xFF5D4037.toInt(), 0xFF37474F.toInt(), 0xFF6A1B9A.toInt(), 0xFF1565C0.toInt(),
        0xFF00695C.toInt(), 0xFFAD1457.toInt(), 0xFF4E342E.toInt(), 0xFF283593.toInt(),
    )

    fun colorFor(title: String): Int = palette[(title.hashCode() and 0x7fffffff) % palette.size]

    fun load(path: String?): Bitmap? {
        if (path == null) return null
        cache.get(path)?.let { return it }
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.also { cache.put(path, it) }
    }

    /** Shows the cover, or a colored placeholder with the title when the book has no cover. */
    fun bind(image: ImageView, placeholder: TextView?, title: String, coverPath: String?) {
        val bmp = load(coverPath)
        if (bmp != null) {
            image.setImageBitmap(bmp)
            image.setBackgroundColor(Color.TRANSPARENT)
            placeholder?.isVisible = false
        } else {
            image.setImageDrawable(null)
            image.setBackgroundColor(colorFor(title))
            placeholder?.isVisible = true
            placeholder?.text = title
        }
    }
}
