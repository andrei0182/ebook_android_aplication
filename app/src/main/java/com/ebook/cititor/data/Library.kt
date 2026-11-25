package com.ebook.cititor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.ebook.cititor.epub.EpubBook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class BookEntry(
    val id: String,
    val title: String,
    val author: String,
    val filePath: String,
    val coverPath: String?,
    val chapterCount: Int,
    val chapter: Int = 0,
    val paragraph: Int = 0,
    val lastOpened: Long = System.currentTimeMillis(),
    val kind: SourceKind = SourceKind.EPUB,
)

/** Stores the imported books and the reading position of each one (JSON file in app storage). */
object Library {
    private var cache: MutableList<BookEntry>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "library.json")

    @Synchronized
    fun all(ctx: Context): List<BookEntry> = load(ctx).sortedByDescending { it.lastOpened }

    @Synchronized
    fun get(ctx: Context, id: String): BookEntry? = load(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun updateProgress(ctx: Context, id: String, chapter: Int, paragraph: Int) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = list[i]
        if (old.chapter == chapter && old.paragraph == paragraph) return
        list[i] = old.copy(chapter = chapter, paragraph = paragraph, lastOpened = System.currentTimeMillis())
        save(ctx)
    }

    @Synchronized
    fun touch(ctx: Context, id: String) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = list[i].copy(lastOpened = System.currentTimeMillis()); save(ctx)
        }
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        val list = load(ctx)
        list.firstOrNull { it.id == id }?.let { e ->
            File(e.filePath).delete()
            e.coverPath?.let { File(it).delete() }
            list.remove(e)
            save(ctx)
        }
    }

    /** Imports an EPUB, PDF or text file. Call off the main thread. */
    fun import(ctx: Context, uri: Uri): BookEntry = add(ctx) { dest -> Importer.fromUri(ctx, uri, dest) }

    /** Imports a web article as a book. Call off the main thread. */
    fun importUrl(ctx: Context, url: String): BookEntry = add(ctx) { dest -> Importer.fromUrl(url, dest) }

    /** Imports pasted or shared text. Call off the main thread. */
    fun importText(ctx: Context, text: String): BookEntry = add(ctx) { dest -> Importer.fromText(text, dest) }

    private fun add(ctx: Context, produce: (File) -> Importer.Result): BookEntry {
        val id = UUID.randomUUID().toString()
        val dir = File(ctx.filesDir, "books").apply { mkdirs() }
        val dest = File(dir, "$id.epub")
        try {
            val res = produce(dest)
            EpubBook.open(dest).use { book ->
                var cover: String? = null
                book.coverPath?.let { cp ->
                    book.entryBytes(cp)?.let { bytes ->
                        val bmp = decodeScaled(bytes, 360)
                        if (bmp != null) {
                            val coverDir = File(ctx.filesDir, "covers").apply { mkdirs() }
                            val cf = File(coverDir, "$id.jpg")
                            cf.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                            cover = cf.absolutePath
                        }
                    }
                }
                val title = book.title.ifBlank { res.fallbackTitle }
                val entry = BookEntry(id, title, book.author, dest.absolutePath, cover, book.spine.size, kind = res.kind)
                synchronized(this) {
                    load(ctx).add(entry); save(ctx)
                }
                return entry
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    private fun decodeScaled(bytes: ByteArray, maxSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxSize && opts.outHeight / (sample * 2) >= maxSize) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun load(ctx: Context): MutableList<BookEntry> {
        cache?.let { return it }
        val list = mutableListOf<BookEntry>()
        val f = file(ctx)
        if (f.exists()) runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    BookEntry(
                        id = o.getString("id"),
                        title = o.optString("title"),
                        author = o.optString("author"),
                        filePath = o.getString("file"),
                        coverPath = o.optString("cover").takeIf { it.isNotEmpty() },
                        chapterCount = o.optInt("chapters"),
                        chapter = o.optInt("chapter"),
                        paragraph = o.optInt("paragraph"),
                        lastOpened = o.optLong("lastOpened"),
                        kind = runCatching { SourceKind.valueOf(o.optString("kind", "EPUB")) }.getOrDefault(SourceKind.EPUB),
                    )
                )
            }
        }
        cache = list
        return list
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        cache.orEmpty().forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("title", e.title); put("author", e.author)
                put("file", e.filePath); put("cover", e.coverPath ?: "")
                put("chapters", e.chapterCount); put("chapter", e.chapter)
                put("paragraph", e.paragraph); put("lastOpened", e.lastOpened)
                put("kind", e.kind.name)
            })
        }
        val tmp = File(ctx.filesDir, "library.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }
}
