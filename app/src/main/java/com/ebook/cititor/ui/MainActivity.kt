package com.ebook.cititor.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.ebook.cititor.R
import com.ebook.cititor.data.BookEntry
import com.ebook.cititor.data.Library
import com.ebook.cititor.data.Prefs
import com.ebook.cititor.databinding.ActivityMainBinding
import com.ebook.cititor.databinding.SheetAddBinding
import com.ebook.cititor.tts.TtsService
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mini: MiniPlayer
    private val adapter = BookAdapter(::openBook, ::listen, ::bookMenu)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runImport(openAfter = false, uris.map { u -> { Library.import(this, u) } })
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        val span = (resources.configuration.screenWidthDp / 125).coerceIn(2, 6)
        binding.list.layoutManager = GridLayoutManager(this, span)
        binding.list.adapter = adapter
        binding.fab.setOnClickListener { showAddSheet() }
        binding.emptyAdd.setOnClickListener { showAddSheet() }
        mini = MiniPlayer(this, binding.mini)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TtsService.state.collect { mini.render(it) }
            }
        }

        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Files opened with the app, and text/links/files shared from other apps. */
    private fun handleIncoming(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri -> runImport(true, listOf { Library.import(this, uri) }) }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                when {
                    stream != null -> runImport(true, listOf { Library.import(this, stream) })
                    !text.isNullOrEmpty() -> {
                        val url = extractUrl(text)
                        if (url != null && text.length < url.length + 200) runImport(true, listOf { Library.importUrl(this, url) })
                        else runImport(true, listOf { Library.importText(this, text) })
                    }
                }
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val m = Patterns.WEB_URL.matcher(text)
        if (!m.find()) return null
        val u = m.group()
        return if (u.startsWith("http")) u else "https://$u"
    }

    private fun refresh() {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) { Library.all(this@MainActivity) }
            adapter.submit(books)
            binding.empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
            binding.list.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- adding

    private fun showAddSheet() {
        val sheet = BottomSheetDialog(this)
        val b = SheetAddBinding.inflate(layoutInflater)
        b.addFile.setOnClickListener {
            sheet.dismiss()
            picker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain", "application/octet-stream"))
        }
        b.addLink.setOnClickListener { sheet.dismiss(); askLink() }
        b.addText.setOnClickListener { sheet.dismiss(); askText() }
        sheet.setContentView(b.root)
        sheet.show()
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }

    private fun inputView(hint: Int, multiline: Boolean, initial: String?): Pair<View, EditText> {
        val edit = EditText(this).apply {
            setHint(hint)
            inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            if (multiline) { minLines = 4; maxLines = 10 }
            setText(initial.orEmpty())
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(edit) }
        return frame to edit
    }

    private fun askLink() {
        val clip = clipboardText()?.let { extractUrl(it) }
        val (view, edit) = inputView(R.string.link_hint, false, clip)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_link)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ ->
                val url = extractUrl(edit.text.toString().trim())
                if (url == null) Toast.makeText(this, R.string.invalid_link, Toast.LENGTH_SHORT).show()
                else runImport(true, listOf { Library.importUrl(this, url) })
            }
            .show()
    }

    private fun askText() {
        val (view, edit) = inputView(R.string.text_hint, true, clipboardText())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_text)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ ->
                val text = edit.text.toString()
                if (text.isNotBlank()) runImport(true, listOf { Library.importText(this, text) })
            }
            .show()
    }

    private fun runImport(openAfter: Boolean, jobs: List<() -> BookEntry>) {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            var last: BookEntry? = null
            val errors = ArrayList<String>()
            for (job in jobs) {
                val res = withContext(Dispatchers.IO) { runCatching { job() } }
                res.onSuccess { last = it }.onFailure { errors.add(it.message ?: it.javaClass.simpleName) }
            }
            binding.progress.visibility = View.GONE
            if (errors.isNotEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.import_failed, errors.first()), Toast.LENGTH_LONG).show()
            }
            refresh()
            if (openAfter) last?.let { openBook(it) }
        }
    }

    // ---------------------------------------------------------------- books

    private fun openBook(book: BookEntry) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id))
    }

    private fun listen(book: BookEntry) {
        val prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.askedNotifications = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val s = TtsService.state.value
        if (!(s.active && s.bookId == book.id)) {
            val fresh = Library.get(this, book.id) ?: book
            TtsService.play(this, book.id, fresh.chapter, fresh.paragraph)
        }
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun bookMenu(book: BookEntry) {
        val options = arrayOf(getString(R.string.listen), getString(R.string.read), getString(R.string.delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(book.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> listen(book)
                    1 -> openBook(book)
                    2 -> confirmDelete(book)
                }
            }
            .show()
    }

    private fun confirmDelete(book: BookEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, book.title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (TtsService.state.value.bookId == book.id) TtsService.command(this, TtsService.ACTION_STOP)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { Library.remove(this@MainActivity, book.id) }
                    refresh()
                }
            }
            .show()
    }
}
