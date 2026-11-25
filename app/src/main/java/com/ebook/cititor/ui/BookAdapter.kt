package com.ebook.cititor.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ebook.cititor.R
import com.ebook.cititor.data.BookEntry
import com.ebook.cititor.databinding.ItemBookBinding

class BookAdapter(
    private val onClick: (BookEntry) -> Unit,
    private val onLongClick: (BookEntry) -> Unit,
) : RecyclerView.Adapter<BookAdapter.Holder>() {

    private var items: List<BookEntry> = emptyList()

    fun submit(list: List<BookEntry>) {
        items = list
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class Holder(val b: ItemBookBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val book = items[position]
        val ctx = h.itemView.context
        h.b.title.text = book.title
        h.b.author.text = book.author.ifBlank { ctx.getString(R.string.unknown_author) }
        val total = book.chapterCount.coerceAtLeast(1)
        val pct = ((book.chapter.toFloat() / total) * 100).toInt().coerceIn(0, 100)
        h.b.progress.progress = pct
        h.b.progressText.text = ctx.getString(R.string.progress_format, book.chapter + 1, total)
        val bmp = book.coverPath?.let { BitmapFactory.decodeFile(it) }
        if (bmp != null) h.b.cover.setImageBitmap(bmp) else h.b.cover.setImageResource(R.drawable.ic_book)
        h.itemView.setOnClickListener { onClick(book) }
        h.itemView.setOnLongClickListener { onLongClick(book); true }
    }
}
