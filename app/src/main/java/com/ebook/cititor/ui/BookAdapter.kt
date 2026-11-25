package com.ebook.cititor.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.ebook.cititor.R
import com.ebook.cititor.data.BookEntry
import com.ebook.cititor.data.SourceKind
import com.ebook.cititor.databinding.ItemBookBinding

class BookAdapter(
    private val onClick: (BookEntry) -> Unit,
    private val onListen: (BookEntry) -> Unit,
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
        h.b.progress.progress = ((book.chapter.toFloat() / total) * 100).toInt().coerceIn(0, 100)
        h.b.progress.isVisible = book.chapter > 0 || book.paragraph > 0
        CoverArt.bind(h.b.cover, h.b.coverTitle, book.title, book.coverPath)
        h.b.badge.isVisible = book.kind != SourceKind.EPUB
        h.b.badge.text = when (book.kind) {
            SourceKind.PDF -> "PDF"
            SourceKind.TXT -> "TXT"
            SourceKind.WEB -> "WEB"
            SourceKind.TEXT -> ctx.getString(R.string.badge_text)
            SourceKind.EPUB -> ""
        }
        h.b.card.setOnClickListener { onClick(book) }
        h.b.card.setOnLongClickListener { onLongClick(book); true }
        h.b.listen.setOnClickListener { onListen(book) }
    }
}
