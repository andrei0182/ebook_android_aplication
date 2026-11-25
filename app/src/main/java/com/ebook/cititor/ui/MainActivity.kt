package com.ebook.cititor.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ebook.cititor.R
import com.ebook.cititor.data.BookEntry
import com.ebook.cititor.data.Library
import com.ebook.cititor.databinding.ActivityMainBinding
import com.ebook.cititor.tts.TtsService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = BookAdapter(::openBook, ::confirmDelete)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importBooks(uris, openAfter = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.list.updatePadding(bottom = bars.bottom + resources.getDimensionPixelSize(R.dimen.fab_space))
            (binding.fab.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin =
                bars.bottom + resources.getDimensionPixelSize(R.dimen.fab_margin)
            binding.fab.requestLayout()
            insets
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.fab.setOnClickListener { launchPicker() }
        binding.emptyAdd.setOnClickListener { launchPicker() }

        // Opened from a file manager ("Open with").
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { importBooks(listOf(it), openAfter = true) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun launchPicker() {
        picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
    }

    private fun refresh() {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) { Library.all(this@MainActivity) }
            adapter.submit(books)
            binding.empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
            binding.list.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun importBooks(uris: List<Uri>, openAfter: Boolean) {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            var last: BookEntry? = null
            var failed = 0
            for (uri in uris) {
                val res = withContext(Dispatchers.IO) { runCatching { Library.import(this@MainActivity, uri) } }
                res.onSuccess { last = it }.onFailure { failed++ }
            }
            binding.progress.visibility = View.GONE
            if (failed > 0) Toast.makeText(this@MainActivity, getString(R.string.import_failed, failed), Toast.LENGTH_LONG).show()
            refresh()
            if (openAfter) last?.let { openBook(it) }
        }
    }

    private fun openBook(book: BookEntry) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id))
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
