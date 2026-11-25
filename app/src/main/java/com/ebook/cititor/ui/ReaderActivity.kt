package com.ebook.cititor.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ebook.cititor.R
import com.ebook.cititor.data.Library
import com.ebook.cititor.data.Prefs
import com.ebook.cititor.data.ReaderTheme
import com.ebook.cititor.databinding.ActivityReaderBinding
import com.ebook.cititor.databinding.DialogSettingsBinding
import com.ebook.cititor.epub.EpubBook
import com.ebook.cititor.tts.PlaybackState
import com.ebook.cititor.tts.TtsService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val HOST = "book.local"
        private const val BASE = "https://$HOST/"
    }

    private lateinit var binding: ActivityReaderBinding
    private lateinit var prefs: Prefs
    private var book: EpubBook? = null
    private var bookId: String = ""
    private var chapter = 0
    private var pendingParagraph = 0
    private var pageReady = false
    private var lastState = PlaybackState()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setupWebView()
        setupControls()
        applyChrome()

        val id = intent.getStringExtra(EXTRA_BOOK_ID)
        if (id == null) {
            finish(); return
        }
        openBook(id)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TtsService.state.collect { onPlaybackState(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getStringExtra(EXTRA_BOOK_ID) ?: return
        if (id != bookId) openBook(id)
    }

    private fun openBook(id: String) {
        val entry = Library.get(this, id)
        if (entry == null) {
            Toast.makeText(this, R.string.book_missing, Toast.LENGTH_LONG).show()
            finish(); return
        }
        bookId = id
        Library.touch(this, id)
        supportActionBar?.title = entry.title
        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { EpubBook.open(File(entry.filePath)) } }
            opened.onSuccess { b ->
                book?.close()
                book = b
                val s = TtsService.state.value
                if (s.active && s.bookId == id) showChapter(s.chapter, s.paragraph)
                else showChapter(entry.chapter.coerceIn(0, b.spine.size - 1), entry.paragraph)
            }.onFailure {
                Toast.makeText(this@ReaderActivity, getString(R.string.open_failed, it.message), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        binding.web.destroy()
        book?.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val web = binding.web
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.builtInZoomControls = false
        web.settings.setSupportZoom(false)
        web.settings.textZoom = prefs.textZoom
        web.setBackgroundColor(Color.parseColor(prefs.theme.bg))
        web.addJavascriptInterface(Bridge(), "Reader")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != HOST) return null
                return serve(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST) {
                    val b = book ?: return true
                    val path = EpubBook.resolvePath("", url.encodedPath.orEmpty())
                    val idx = b.spineIndexOf(path)
                    if (idx >= 0 && idx != chapter) showChapter(idx, 0)
                    else url.fragment?.let { f -> view.evaluateJavascript("__anchor(${jsString(f)})", null) }
                    return true
                }
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                val s = lastState
                if (s.active && s.bookId == bookId && s.chapter == chapter) {
                    view.evaluateJavascript("__hl(${s.paragraph}, ${s.word}, true)", null)
                } else {
                    view.evaluateJavascript("__goto($pendingParagraph)", null)
                }
            }
        }
    }

    private fun serve(url: Uri): WebResourceResponse {
        val b = book ?: return notFound()
        val path = EpubBook.resolvePath("", url.encodedPath.orEmpty())
        return try {
            if (b.isHtml(path) && b.spineIndexOf(path) >= 0) {
                val bytes = b.entryBytes(path) ?: return notFound()
                val html = EpubBook.renderHtml(String(bytes, Charsets.UTF_8), headInjection()).html
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
            } else {
                val bytes = b.entryBytes(path) ?: return notFound()
                val type = b.mediaTypeOf(path)
                WebResourceResponse(type, if (type.startsWith("text/")) "utf-8" else null, ByteArrayInputStream(bytes))
            }
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun readerCss(): String {
        val t = prefs.theme
        val font = if (prefs.serif) "Georgia, 'Noto Serif', serif" else "'Roboto', 'Noto Sans', sans-serif"
        return """
            html, body { background: ${t.bg} !important; color: ${t.fg} !important; }
            body { margin: 0 !important; padding: 12px 18px 48px !important; line-height: 1.6 !important;
                   font-family: $font !important; word-wrap: break-word; -webkit-tap-highlight-color: transparent; }
            body * { color: inherit !important; background-color: transparent !important; font-family: inherit !important; }
            body a, body a * { color: ${t.link} !important; }
            img, svg, video { max-width: 100% !important; height: auto !important; }
            pre { white-space: pre-wrap; }
            body .__tts, body .__tts * { background-color: ${t.highlight} !important; border-radius: 4px; }
            body .__tts .__wd, body .__wd { background-color: ${t.word} !important; border-radius: 3px;
                   box-shadow: 0 0 0 2px ${t.word}; }
        """.trimIndent()
    }

    private fun headInjection(): String = "<style id=\"__rs\">${readerCss()}</style><script>$JS</script>"

    private fun applyStyle() {
        binding.web.settings.textZoom = prefs.textZoom
        binding.web.setBackgroundColor(Color.parseColor(prefs.theme.bg))
        binding.web.evaluateJavascript("__css(${jsString(readerCss())})", null)
        applyChrome()
    }

    private fun applyChrome() {
        val t = prefs.theme
        binding.root.setBackgroundColor(Color.parseColor(t.bg))
    }

    private fun showChapter(index: Int, paragraph: Int) {
        val b = book ?: return
        if (index !in b.spine.indices) return
        chapter = index
        pendingParagraph = paragraph
        pageReady = false
        supportActionBar?.subtitle = b.chapterTitle(index)
        binding.web.loadUrl(BASE + encodePath(b.spine[index].path))
        Library.updateProgress(this, bookId, chapter, paragraph)
        binding.chapterInfo.text = getString(R.string.chapter_of, index + 1, b.spine.size)
    }

    private fun encodePath(path: String) = path.split('/').joinToString("/") { Uri.encode(it) }

    private fun jsString(s: String): String = org.json.JSONObject.quote(s)

    private inner class Bridge {
        @JavascriptInterface
        fun onPosition(p: Int) {
            runOnUiThread {
                val s = lastState
                if (s.active && s.bookId == bookId) return@runOnUiThread // the speaker drives progress
                if (pageReady) Library.updateProgress(this@ReaderActivity, bookId, chapter, p)
            }
        }

        @JavascriptInterface
        fun onTap() {
            runOnUiThread { toggleChrome() }
        }

        @JavascriptInterface
        fun onReadFrom(p: Int) {
            runOnUiThread { startListening(chapter, p) }
        }

        @JavascriptInterface
        fun onEdge(next: Boolean) {
            runOnUiThread {
                val b = book ?: return@runOnUiThread
                if (next && chapter < b.spine.size - 1) showChapter(chapter + 1, 0)
                else if (!next && chapter > 0) showChapter(chapter - 1, Int.MAX_VALUE)
            }
        }
    }

    private fun toggleChrome() {
        val show = !binding.appBar.isVisible
        binding.appBar.isVisible = show
        binding.bottomBar.isVisible = show
    }

    // ------------------------------------------------------------------ controls

    private fun setupControls() {
        binding.btnPrevChapter.setOnClickListener {
            if (isSpeakingThisBook()) TtsService.play(this, bookId, (chapter - 1).coerceAtLeast(0), 0)
            else if (chapter > 0) showChapter(chapter - 1, 0)
        }
        binding.btnNextChapter.setOnClickListener {
            val b = book ?: return@setOnClickListener
            if (chapter >= b.spine.size - 1) return@setOnClickListener
            if (isSpeakingThisBook()) TtsService.play(this, bookId, chapter + 1, 0)
            else showChapter(chapter + 1, 0)
        }
        binding.btnRewind.setOnClickListener {
            if (isSpeakingThisBook()) TtsService.command(this, TtsService.ACTION_PREV)
            else binding.web.evaluateJavascript("__page(-1)", null)
        }
        binding.btnForward.setOnClickListener {
            if (isSpeakingThisBook()) TtsService.command(this, TtsService.ACTION_NEXT)
            else binding.web.evaluateJavascript("__page(1)", null)
        }
        binding.btnPlayer.setOnClickListener {
            if (!isSpeakingThisBook()) {
                binding.web.evaluateJavascript("__first()") { v ->
                    startListening(chapter, v.toIntOrNull() ?: 0)
                    startActivity(Intent(this, PlayerActivity::class.java))
                }
            } else startActivity(Intent(this, PlayerActivity::class.java))
        }
        binding.btnPlay.setOnClickListener {
            val s = lastState
            if (s.active && s.bookId == bookId) {
                if (s.chapter != chapter && !s.playing) {
                    binding.web.evaluateJavascript("__first()") { v -> startListening(chapter, v.toIntOrNull() ?: 0) }
                } else TtsService.command(this, TtsService.ACTION_TOGGLE)
            } else {
                binding.web.evaluateJavascript("__first()") { v -> startListening(chapter, v.toIntOrNull() ?: 0) }
            }
        }
    }

    private fun isSpeakingThisBook() = lastState.active && lastState.bookId == bookId

    private fun startListening(ch: Int, paragraph: Int) {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.askedNotifications = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        TtsService.play(this, bookId, ch, paragraph)
    }

    private fun onPlaybackState(s: PlaybackState) {
        val prev = lastState
        lastState = s
        if (s.errorSeq != prev.errorSeq && s.error != null) Toast.makeText(this, s.error, Toast.LENGTH_LONG).show()
        val mine = s.active && s.bookId == bookId
        binding.btnPlay.setImageResource(
            if (mine && s.playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlay.contentDescription = getString(if (mine && s.playing) R.string.pause else R.string.listen)
        binding.loading.isVisible = mine && s.loading
        invalidateOptionsMenu()
        if (!mine) {
            if (prev.active && prev.bookId == bookId && pageReady) binding.web.evaluateJavascript("__hl(-1, -1, false)", null)
            return
        }
        if (s.loading) return
        if (s.chapter != chapter && book != null) {
            showChapter(s.chapter, s.paragraph)
        } else if (pageReady && (s.paragraph != prev.paragraph || s.word != prev.word || s.chapter != prev.chapter || !prev.active)) {
            binding.web.evaluateJavascript("__hl(${s.paragraph}, ${s.word}, true)", null)
        }
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val mine = lastState.active && lastState.bookId == bookId
        menu.findItem(R.id.action_stop_audio)?.isVisible = mine
        menu.findItem(R.id.action_sleep)?.isVisible = mine
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_toc -> showToc()
            R.id.action_settings -> showSettings()
            R.id.action_sleep -> PlaybackDialogs.sleep(this)
            R.id.action_voice -> startActivity(
                Intent(this, VoicesActivity::class.java).putExtra(VoicesActivity.EXTRA_LANGUAGE, book?.language)
            )
            R.id.action_stop_audio -> TtsService.command(this, TtsService.ACTION_STOP)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showToc() {
        val b = book ?: return
        val entries: List<Pair<String, Int>> = if (b.toc.isNotEmpty())
            b.toc.map { ("    ".repeat(it.depth.coerceAtMost(4)) + it.title) to it.spineIndex }
        else b.spine.indices.map { b.chapterTitle(it) to it }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries.map { it.first })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.toc)
            .setAdapter(adapter) { _, which ->
                val idx = entries[which].second
                if (isSpeakingThisBook()) TtsService.play(this, bookId, idx, 0) else showChapter(idx, 0)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showSettings() {
        val d = DialogSettingsBinding.inflate(layoutInflater)
        // Text size: 60%..250%
        d.textSize.max = 190
        d.textSize.progress = prefs.textZoom - 60
        d.textSizeValue.text = "${prefs.textZoom}%"
        d.textSize.setOnSeekBarChangeListener(seek { v ->
            prefs.textZoom = v + 60
            d.textSizeValue.text = "${prefs.textZoom}%"
            applyStyle()
        })
        when (prefs.theme) {
            ReaderTheme.LIGHT -> d.themeLight.isChecked = true
            ReaderTheme.SEPIA -> d.themeSepia.isChecked = true
            ReaderTheme.DARK -> d.themeDark.isChecked = true
        }
        d.themeGroup.setOnCheckedChangeListener { _, id ->
            prefs.theme = when (id) {
                R.id.theme_sepia -> ReaderTheme.SEPIA
                R.id.theme_dark -> ReaderTheme.DARK
                else -> ReaderTheme.LIGHT
            }
            applyStyle()
        }
        d.serif.isChecked = prefs.serif
        d.serif.setOnCheckedChangeListener { _, checked -> prefs.serif = checked; applyStyle() }

        // Speech rate 0.5x..3.0x in 0.1 steps, pitch 0.5..2.0
        d.rate.max = 25
        d.rate.progress = ((prefs.speechRate - 0.5f) * 10).toInt()
        d.rateValue.text = fmt(prefs.speechRate)
        d.rate.setOnSeekBarChangeListener(seek(onStop = ::ttsSettingsChanged) { v ->
            prefs.speechRate = 0.5f + v / 10f
            d.rateValue.text = fmt(prefs.speechRate)
        })
        d.pitch.max = 15
        d.pitch.progress = ((prefs.speechPitch - 0.5f) * 10).toInt()
        d.pitchValue.text = fmt(prefs.speechPitch)
        d.pitch.setOnSeekBarChangeListener(seek(onStop = ::ttsSettingsChanged) { v ->
            prefs.speechPitch = 0.5f + v / 10f
            d.pitchValue.text = fmt(prefs.speechPitch)
        })
        d.ttsSystem.setOnClickListener {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { Toast.makeText(this, R.string.tts_settings_missing, Toast.LENGTH_SHORT).show() }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(d.root)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun ttsSettingsChanged() {
        if (isSpeakingThisBook()) TtsService.command(this, TtsService.ACTION_SETTINGS)
    }

    private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.1fx", v)

    private fun seek(onStop: () -> Unit = {}, onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) = onStop()
    }
}

/** Script injected into every chapter: highlighting, position tracking and gestures. */
private const val JS = """
(function(){
  var cur = -1;
  function el(i){ return document.querySelector('[data-tts="'+i+'"]'); }
  var curWords = [];
  window.__hl = function(i, w, scroll){
    if (cur >= 0 && cur !== i) { var o = el(cur); if (o) o.classList.remove('__tts'); }
    for (var k = 0; k < curWords.length; k++) curWords[k].classList.remove('__wd');
    curWords = [];
    cur = i;
    if (i < 0) return;
    var e = el(i);
    if (!e) return;
    e.classList.add('__tts');
    var target = e;
    if (w >= 0) {
      var ws = e.querySelectorAll('[data-w="'+w+'"]');
      for (var k = 0; k < ws.length; k++) { ws[k].classList.add('__wd'); curWords.push(ws[k]); }
      if (ws.length) target = ws[0];
    }
    if (scroll) {
      var r = target.getBoundingClientRect();
      if (r.top < window.innerHeight * 0.08 || r.bottom > window.innerHeight * 0.85) {
        if (target === e && r.height > window.innerHeight * 0.7 && r.top < 0) return;
        target.scrollIntoView({block:'center', behavior:'smooth'});
      }
    }
  };
  window.__first = function(){
    var els = document.querySelectorAll('[data-tts]');
    for (var k = 0; k < els.length; k++) {
      var r = els[k].getBoundingClientRect();
      if (r.bottom > 4) return parseInt(els[k].getAttribute('data-tts'));
    }
    return 0;
  };
  window.__goto = function(i){
    if (i <= 0) { window.scrollTo(0, 0); return; }
    var e = el(i);
    if (!e) { var all = document.querySelectorAll('[data-tts]'); e = all[all.length-1]; }
    if (e) e.scrollIntoView({block:'start'});
  };
  window.__anchor = function(id){ var e = document.getElementById(id); if (e) e.scrollIntoView({block:'start'}); };
  window.__css = function(c){ var s = document.getElementById('__rs'); if (s) s.textContent = c; };
  window.__page = function(dir){
    var h = window.innerHeight * 0.9;
    var atEnd = window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 2;
    var atTop = window.scrollY <= 0;
    if (dir > 0 && atEnd) { Reader.onEdge(true); return; }
    if (dir < 0 && atTop) { Reader.onEdge(false); return; }
    window.scrollBy({top: dir * h, behavior: 'smooth'});
  };
  var t = null;
  window.addEventListener('scroll', function(){
    clearTimeout(t);
    t = setTimeout(function(){ Reader.onPosition(__first()); }, 400);
  });
  var tapTimer = null;
  document.addEventListener('click', function(e){
    if (e.target.closest && e.target.closest('a')) return;
    if (tapTimer) return;
    tapTimer = setTimeout(function(){ tapTimer = null; Reader.onTap(); }, 280);
  });
  document.addEventListener('dblclick', function(e){
    if (tapTimer) { clearTimeout(tapTimer); tapTimer = null; }
    var p = e.target.closest ? e.target.closest('[data-tts]') : null;
    if (p) Reader.onReadFrom(parseInt(p.getAttribute('data-tts')));
  });
})();
"""
