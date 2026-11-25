package com.ebook.cititor.tts

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ebook.cititor.R
import com.ebook.cititor.data.Library
import com.ebook.cititor.data.Prefs
import com.ebook.cititor.epub.EpubBook
import com.ebook.cititor.ui.ReaderActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

data class PlaybackState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val bookId: String? = null,
    val chapter: Int = 0,
    val paragraph: Int = 0,
    val sleepAt: Long = 0L,
    val error: String? = null,
)

/**
 * Foreground service that reads a book aloud with Android's TextToSpeech engine, so listening
 * continues with the screen off or while using other apps.
 */
class TtsService : Service() {

    companion object {
        const val ACTION_PLAY = "com.ebook.cititor.PLAY"       // book_id, chapter, paragraph
        const val ACTION_TOGGLE = "com.ebook.cititor.TOGGLE"
        const val ACTION_PAUSE = "com.ebook.cititor.PAUSE"
        const val ACTION_RESUME = "com.ebook.cititor.RESUME"
        const val ACTION_NEXT = "com.ebook.cititor.NEXT"
        const val ACTION_PREV = "com.ebook.cititor.PREV"
        const val ACTION_STOP = "com.ebook.cititor.STOP"
        const val ACTION_SETTINGS = "com.ebook.cititor.SETTINGS"
        const val ACTION_SLEEP = "com.ebook.cititor.SLEEP"     // minutes (0 = off)

        const val EXTRA_BOOK = "book_id"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_PARAGRAPH = "paragraph"
        const val EXTRA_MINUTES = "minutes"

        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 42

        private val _state = MutableStateFlow(PlaybackState())
        val state: StateFlow<PlaybackState> = _state

        fun play(ctx: Context, bookId: String, chapter: Int, paragraph: Int) {
            val i = Intent(ctx, TtsService::class.java).setAction(ACTION_PLAY)
                .putExtra(EXTRA_BOOK, bookId).putExtra(EXTRA_CHAPTER, chapter).putExtra(EXTRA_PARAGRAPH, paragraph)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun command(ctx: Context, action: String, extras: Bundle? = null) {
            if (!_state.value.active && action != ACTION_PLAY) return
            val i = Intent(ctx, TtsService::class.java).setAction(action)
            extras?.let { i.putExtras(it) }
            ctx.startService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingStart: (() -> Unit)? = null

    private var book: EpubBook? = null
    private var bookId: String? = null
    private var bookTitle = ""
    private var chapter = 0
    private var paragraphs: List<String> = emptyList()
    private var paragraph = 0
    private var playing = false
    private var generation = 0

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sleepAt = 0L
    private val sleepRunnable = Runnable { sleepAt = 0; pause() }
    private var noisyRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> main.post { pause() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannel()
        session = MediaSessionCompat(this, "CititorEpub").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
                override fun onFastForward() = skip(1)
                override fun onRewind() = skip(-1)
                override fun onStop() = stopAll()
            })
            isActive = true
        }
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts?.setOnUtteranceProgressListener(progressListener)
                    pendingStart?.invoke()
                    pendingStart = null
                } else {
                    publish(error = "Motorul Text-to-Speech nu este disponibil. Instalează „Google Text-to-Speech” din Magazin Play.")
                    stopAll()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter foreground first: required after startForegroundService().
        goForeground()
        when (intent?.action) {
            ACTION_PLAY -> {
                val id = intent.getStringExtra(EXTRA_BOOK) ?: return START_NOT_STICKY
                val ch = intent.getIntExtra(EXTRA_CHAPTER, 0)
                val p = intent.getIntExtra(EXTRA_PARAGRAPH, 0)
                val start = { startBook(id, ch, p) }
                if (ttsReady) start() else pendingStart = start
            }
            ACTION_TOGGLE -> if (playing) pause() else resume()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> skip(1)
            ACTION_PREV -> skip(-1)
            ACTION_STOP -> stopAll()
            ACTION_SETTINGS -> if (playing) speakFrom(paragraph)
            ACTION_SLEEP -> setSleep(intent.getIntExtra(EXTRA_MINUTES, 0))
            null -> if (bookId == null) stopAll()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ playback

    private fun startBook(id: String, ch: Int, p: Int) {
        if (id == bookId && book != null) {
            if (ch == chapter) {
                paragraph = p.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
                playing = true
                speakFrom(paragraph)
            } else {
                loadChapter(ch, p, autoplay = true)
            }
            return
        }
        val entry = Library.get(this, id)
        if (entry == null) {
            publish(error = "Cartea nu a fost găsită"); stopAll(); return
        }
        tts?.stop()
        generation++
        publish(loading = true)
        io.execute {
            val opened = runCatching { EpubBook.open(File(entry.filePath)) }
            main.post {
                opened.onSuccess { b ->
                    book?.close()
                    book = b
                    bookId = id
                    bookTitle = entry.title
                    applyLanguage(b.language)
                    loadChapter(ch, p, autoplay = true)
                }.onFailure {
                    publish(error = "Cartea nu poate fi deschisă: ${it.message}")
                    stopAll()
                }
            }
        }
    }

    private fun applyLanguage(lang: String?) {
        val engine = tts ?: return
        if (lang.isNullOrBlank()) return
        val locale = Locale.forLanguageTag(lang)
        val res = engine.isLanguageAvailable(locale)
        if (res >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
    }

    private fun loadChapter(ch: Int, p: Int, autoplay: Boolean) {
        val b = book ?: return
        if (ch !in b.spine.indices) {
            // End of the book.
            playing = false
            paragraph = 0
            abandonFocus()
            publish()
            return
        }
        tts?.stop()
        val gen = ++generation
        publish(loading = true)
        io.execute {
            val paras = runCatching { b.loadChapter(ch).paragraphs }.getOrDefault(emptyList())
            main.post {
                if (gen != generation) return@post
                chapter = ch
                paragraphs = paras
                paragraph = p.coerceIn(0, (paras.size - 1).coerceAtLeast(0))
                bookId?.let { Library.updateProgress(this, it, chapter, paragraph) }
                if (paras.isEmpty()) {
                    // Image-only or empty section: move on.
                    if (autoplay) loadChapter(ch + 1, 0, true) else publish()
                    return@post
                }
                if (autoplay) {
                    playing = true
                    speakFrom(paragraph)
                } else publish()
            }
        }
    }

    /** Queues all remaining paragraphs of the chapter, so speech is gapless. */
    private fun speakFrom(start: Int) {
        val engine = tts ?: return
        if (!requestFocus()) {
            playing = false; publish(); return
        }
        registerNoisy()
        acquireWake()
        engine.stop()
        val gen = ++generation
        engine.setSpeechRate(prefs.speechRate)
        engine.setPitch(prefs.speechPitch)
        val maxLen = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        var first = true
        for (i in start until paragraphs.size) {
            val chunks = splitText(paragraphs[i], maxLen)
            chunks.forEachIndexed { k, chunk ->
                val last = i == paragraphs.size - 1 && k == chunks.size - 1
                val uid = "$gen:$i:$k:${if (last) 1 else 0}"
                engine.speak(chunk, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, uid)
                first = false
            }
            // Small pause between paragraphs.
            engine.playSilentUtterance(250, TextToSpeech.QUEUE_ADD, "$gen:s:0:0")
        }
        paragraph = start
        playing = true
        publish()
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val parts = utteranceId?.split(':') ?: return
            if (parts.size < 4 || parts[1] == "s") return
            val gen = parts[0].toIntOrNull() ?: return
            val p = parts[1].toIntOrNull() ?: return
            main.post {
                if (gen != generation || !playing) return@post
                if (p != paragraph) {
                    paragraph = p
                    bookId?.let { Library.updateProgress(this@TtsService, it, chapter, paragraph) }
                    publish()
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            val parts = utteranceId?.split(':') ?: return
            if (parts.size < 4 || parts[3] != "1") return
            val gen = parts[0].toIntOrNull() ?: return
            main.post {
                if (gen != generation || !playing) return@post
                loadChapter(chapter + 1, 0, autoplay = true)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onDone(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = onDone(utteranceId)
    }

    private fun pause() {
        if (!playing) return
        playing = false
        generation++
        tts?.stop()
        releaseWake()
        unregisterNoisy()
        bookId?.let { Library.updateProgress(this, it, chapter, paragraph) }
        publish()
    }

    private fun resume() {
        if (playing || book == null) return
        if (paragraphs.isEmpty()) {
            loadChapter(chapter, 0, autoplay = true); return
        }
        playing = true
        speakFrom(paragraph)
    }

    private fun skip(delta: Int) {
        if (book == null) return
        val target = paragraph + delta
        when {
            target < 0 -> if (chapter > 0) loadChapter(chapter - 1, 0, autoplay = playing)
            target >= paragraphs.size -> loadChapter(chapter + 1, 0, autoplay = playing)
            else -> {
                paragraph = target
                if (playing) speakFrom(paragraph) else {
                    bookId?.let { Library.updateProgress(this, it, chapter, paragraph) }
                    publish()
                }
            }
        }
    }

    private fun setSleep(minutes: Int) {
        main.removeCallbacks(sleepRunnable)
        if (minutes > 0) {
            sleepAt = System.currentTimeMillis() + minutes * 60_000L
            main.postDelayed(sleepRunnable, minutes * 60_000L)
        } else sleepAt = 0
        publish()
    }

    private fun stopAll() {
        playing = false
        generation++
        tts?.stop()
        main.removeCallbacks(sleepRunnable)
        sleepAt = 0
        bookId?.let { Library.updateProgress(this, it, chapter, paragraph) }
        releaseWake()
        unregisterNoisy()
        abandonFocus()
        _state.value = PlaybackState(error = _state.value.error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        session.isActive = false
        session.release()
        book?.close()
        io.shutdown()
        releaseWake()
        unregisterNoisy()
        abandonFocus()
        if (_state.value.active) _state.value = PlaybackState()
        super.onDestroy()
    }

    // --------------------------------------------------------- state & notification

    private fun publish(loading: Boolean = false, error: String? = null) {
        _state.value = PlaybackState(
            active = true, playing = playing, loading = loading, bookId = bookId,
            chapter = chapter, paragraph = paragraph, sleepAt = sleepAt, error = error,
        )
        updateSession()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (bookId != null) nm.notify(NOTIF_ID, buildNotification())
    }

    private fun updateSession() {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                ).build()
        )
        val chapterTitle = book?.chapterTitle(chapter).orEmpty()
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapterTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, bookTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, bookTitle)
                .build()
        )
    }

    private fun goForeground() {
        val notif = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    private fun servicePending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, TtsService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(): Notification {
        val open = Intent(this, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(bookTitle.ifEmpty { getString(R.string.app_name) })
            .setContentText(book?.chapterTitle(chapter) ?: getString(R.string.preparing))
            .setContentIntent(contentIntent)
            .setDeleteIntent(servicePending(ACTION_STOP, 5))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_rew, getString(R.string.previous), servicePending(ACTION_PREV, 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (playing) R.string.pause else R.string.play),
                servicePending(ACTION_TOGGLE, 2)
            )
            .addAction(android.R.drawable.ic_media_ff, getString(R.string.next), servicePending(ACTION_NEXT, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), servicePending(ACTION_STOP, 4))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    // ------------------------------------------------------------ audio plumbing

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener(focusListener, main)
            .build().also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        val wl = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CititorEpub:tts").also {
                it.setReferenceCounted(false); wakeLock = it
            }
        if (!wl.isHeld) wl.acquire()
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /** Splits long paragraphs at sentence boundaries so each piece fits the TTS input limit. */
    private fun splitText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > maxLen) {
            val window = rest.substring(0, maxLen)
            var cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "))
            if (cut < maxLen / 3) cut = window.lastIndexOf(' ')
            if (cut <= 0) cut = maxLen - 1
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out.add(rest.trim())
        return out
    }
}
