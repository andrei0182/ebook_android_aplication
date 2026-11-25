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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import com.ebook.cititor.epub.Paragraph
import com.ebook.cititor.ui.PlayerActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

data class PlaybackState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val bookId: String? = null,
    val bookTitle: String = "",
    val author: String = "",
    val coverPath: String? = null,
    val chapter: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String = "",
    val paragraph: Int = 0,
    val paragraphCount: Int = 0,
    val word: Int = -1,
    val text: Paragraph? = null,
    val sleepAt: Long = 0L,
    val engine: String = Prefs.ENGINE_SYSTEM,
    val error: String? = null,
    val errorSeq: Int = 0,
)

/**
 * Foreground service that reads a book aloud (Android TTS or ElevenLabs AI voices), so listening
 * continues with the screen off or while using other apps.
 */
class TtsService : Service(), Speaker.Listener {

    companion object {
        const val ACTION_PLAY = "com.ebook.cititor.PLAY"       // book_id, chapter, paragraph
        const val ACTION_TOGGLE = "com.ebook.cititor.TOGGLE"
        const val ACTION_PAUSE = "com.ebook.cititor.PAUSE"
        const val ACTION_RESUME = "com.ebook.cititor.RESUME"
        const val ACTION_NEXT = "com.ebook.cititor.NEXT"
        const val ACTION_PREV = "com.ebook.cititor.PREV"
        const val ACTION_STOP = "com.ebook.cititor.STOP"
        const val ACTION_SETTINGS = "com.ebook.cititor.SETTINGS"
        const val ACTION_SLEEP = "com.ebook.cititor.SLEEP"     // minutes (0 = off, -1 = end of chapter)

        const val EXTRA_BOOK = "book_id"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_PARAGRAPH = "paragraph"
        const val EXTRA_MINUTES = "minutes"
        const val SLEEP_END_OF_CHAPTER = -1

        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 42

        private val _state = MutableStateFlow(PlaybackState())
        val state: StateFlow<PlaybackState> = _state
        private var errorSeq = 0

        fun play(ctx: Context, bookId: String, chapter: Int, paragraph: Int) {
            val i = Intent(ctx, TtsService::class.java).setAction(ACTION_PLAY)
                .putExtra(EXTRA_BOOK, bookId).putExtra(EXTRA_CHAPTER, chapter).putExtra(EXTRA_PARAGRAPH, paragraph)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun command(ctx: Context, action: String, extras: Bundle? = null) {
            if (!_state.value.active) return
            val i = Intent(ctx, TtsService::class.java).setAction(action)
            extras?.let { i.putExtras(it) }
            ctx.startService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private var speakerEngine = ""

    private var book: EpubBook? = null
    private var bookId: String? = null
    private var bookTitle = ""
    private var author = ""
    private var coverPath: String? = null
    private var coverBitmap: Bitmap? = null
    private var chapter = 0
    private var paragraphs: List<Paragraph> = emptyList()
    private var paragraph = 0
    private var word = -1
    private var playing = false
    private var loading = false
    private var loadGeneration = 0
    private var lastNotifiedKey = ""

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sleepAt = 0L
    private var sleepEndOfChapter = false
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
            setSessionActivity(openPlayerIntent())
            isActive = true
        }
        createSpeaker()
    }

    private fun createSpeaker() {
        speakerEngine = prefs.engine
        speaker = when (speakerEngine) {
            Prefs.ENGINE_ELEVEN -> ElevenLabsSpeaker(this, this)
            Prefs.ENGINE_PIPER -> PiperSpeaker(this, this)
            else -> SystemSpeaker(this, this)
        }
        book?.let { speaker.setLanguage(it.language) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter foreground first: required after startForegroundService().
        goForeground()
        when (intent?.action) {
            ACTION_PLAY -> {
                val id = intent.getStringExtra(EXTRA_BOOK) ?: return START_NOT_STICKY
                startBook(id, intent.getIntExtra(EXTRA_CHAPTER, 0), intent.getIntExtra(EXTRA_PARAGRAPH, 0))
            }
            ACTION_TOGGLE -> if (playing) pause() else resume()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> skip(1)
            ACTION_PREV -> skip(-1)
            ACTION_STOP -> stopAll()
            ACTION_SETTINGS -> onSettingsChanged()
            ACTION_SLEEP -> setSleep(intent.getIntExtra(EXTRA_MINUTES, 0))
            null -> if (bookId == null) stopAll()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ playback

    private fun startBook(id: String, ch: Int, p: Int) {
        if (prefs.engine != speakerEngine) {
            speaker.release(); createSpeaker()
        }
        if (id == bookId && book != null) {
            if (ch == chapter && !loading && paragraphs.isNotEmpty()) {
                paragraph = p.coerceIn(0, paragraphs.size - 1)
                speakFrom(paragraph)
            } else loadChapter(ch, p, autoplay = true)
            return
        }
        val entry = Library.get(this, id)
        if (entry == null) {
            fail("Cartea nu a fost găsită"); stopAll(); return
        }
        speaker.stop()
        playing = false
        loading = true
        val gen = ++loadGeneration
        bookId = id
        bookTitle = entry.title
        author = entry.author
        coverPath = entry.coverPath
        coverBitmap = entry.coverPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        publish()
        io.execute {
            val opened = runCatching { EpubBook.open(File(entry.filePath)) }
            main.post {
                if (gen != loadGeneration) { opened.getOrNull()?.close(); return@post }
                opened.onSuccess { b ->
                    book?.close()
                    book = b
                    speaker.setLanguage(b.language)
                    loadChapter(ch, p, autoplay = true)
                }.onFailure {
                    fail("Cartea nu poate fi deschisă: ${it.message}")
                    stopAll()
                }
            }
        }
    }

    private fun loadChapter(ch: Int, p: Int, autoplay: Boolean) {
        val b = book ?: return
        if (ch !in b.spine.indices) {
            // End of the book.
            speaker.stop()
            playing = false
            loading = false
            paragraph = 0
            word = -1
            releaseWake()
            abandonFocus()
            publish()
            return
        }
        speaker.stop()
        val gen = ++loadGeneration
        loading = true
        publish()
        io.execute {
            val paras = runCatching { b.loadChapter(ch).paragraphs }.getOrDefault(emptyList())
            main.post {
                if (gen != loadGeneration) return@post
                loading = false
                chapter = ch
                paragraphs = paras
                paragraph = p.coerceIn(0, (paras.size - 1).coerceAtLeast(0))
                word = -1
                saveProgress()
                if (paras.isEmpty()) {
                    // Image-only or empty section: move on.
                    if (autoplay) loadChapter(ch + 1, 0, true) else publish()
                    return@post
                }
                if (autoplay) speakFrom(paragraph) else publish()
            }
        }
    }

    private fun speakFrom(start: Int) {
        if (!requestFocus()) {
            playing = false; publish(); return
        }
        registerNoisy()
        acquireWake()
        paragraph = start
        word = -1
        playing = true
        speaker.speak(paragraphs, start)
        publish()
    }

    // Speaker.Listener ---------------------------------------------------------

    override fun onParagraph(index: Int) {
        if (!playing) return
        if (index != paragraph) {
            paragraph = index
            word = -1
            saveProgress()
            publish()
        }
    }

    override fun onWord(index: Int, word: Int) {
        if (!playing || index != paragraph) return
        this.word = word
        publish()
    }

    override fun onFinished() {
        if (!playing) return
        if (sleepEndOfChapter) {
            sleepEndOfChapter = false
            playing = false
            loadChapter(chapter + 1, 0, autoplay = false)
            releaseWake(); unregisterNoisy()
            return
        }
        loadChapter(chapter + 1, 0, autoplay = true)
    }

    override fun onSpeakerError(message: String) {
        fail(message)
        pause()
    }

    private fun pause() {
        if (!playing) return
        playing = false
        speaker.stop()
        releaseWake()
        unregisterNoisy()
        saveProgress()
        publish()
    }

    private fun resume() {
        if (playing || book == null || loading) return
        if (prefs.engine != speakerEngine) {
            speaker.release(); createSpeaker()
        }
        if (paragraphs.isEmpty()) {
            loadChapter(chapter, 0, autoplay = true); return
        }
        speakFrom(paragraph)
    }

    private fun skip(delta: Int) {
        if (book == null || loading) return
        val target = paragraph + delta
        when {
            target < 0 -> if (chapter > 0) loadChapter(chapter - 1, 0, autoplay = playing)
            target >= paragraphs.size -> loadChapter(chapter + 1, 0, autoplay = playing)
            else -> {
                paragraph = target
                word = -1
                if (playing) speakFrom(paragraph) else {
                    saveProgress(); publish()
                }
            }
        }
    }

    private fun onSettingsChanged() {
        if (prefs.engine != speakerEngine) {
            val wasPlaying = playing
            speaker.release()
            createSpeaker()
            if (wasPlaying) speakFrom(paragraph) else publish()
            return
        }
        if (speaker.applySettings() && playing) speakFrom(paragraph)
    }

    private fun setSleep(minutes: Int) {
        main.removeCallbacks(sleepRunnable)
        sleepEndOfChapter = minutes == SLEEP_END_OF_CHAPTER
        if (minutes > 0) {
            sleepAt = System.currentTimeMillis() + minutes * 60_000L
            main.postDelayed(sleepRunnable, minutes * 60_000L)
        } else sleepAt = 0
        publish()
    }

    private fun stopAll() {
        playing = false
        loadGeneration++
        speaker.stop()
        main.removeCallbacks(sleepRunnable)
        sleepAt = 0
        sleepEndOfChapter = false
        saveProgress()
        releaseWake()
        unregisterNoisy()
        abandonFocus()
        _state.value = PlaybackState(error = _state.value.error, errorSeq = _state.value.errorSeq)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveProgress() {
        bookId?.let { Library.updateProgress(this, it, chapter, paragraph) }
    }

    private fun fail(message: String) {
        errorSeq++
        _state.value = _state.value.copy(error = message, errorSeq = errorSeq)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        speaker.release()
        session.isActive = false
        session.release()
        book?.close()
        io.shutdown()
        releaseWake()
        unregisterNoisy()
        abandonFocus()
        if (_state.value.active) _state.value = PlaybackState(error = _state.value.error, errorSeq = _state.value.errorSeq)
        super.onDestroy()
    }

    // --------------------------------------------------------- state & notification

    private fun publish() {
        val b = book
        _state.value = PlaybackState(
            active = true, playing = playing, loading = loading, bookId = bookId,
            bookTitle = bookTitle, author = author, coverPath = coverPath,
            chapter = chapter, chapterCount = b?.spine?.size ?: 0,
            chapterTitle = b?.chapterTitle(chapter).orEmpty(),
            paragraph = paragraph, paragraphCount = paragraphs.size, word = word,
            text = paragraphs.getOrNull(paragraph),
            sleepAt = if (sleepEndOfChapter) -1L else sleepAt, engine = speakerEngine,
            error = _state.value.error, errorSeq = _state.value.errorSeq,
        )
        // Word updates are frequent; only refresh session/notification when something visible changed.
        val key = "$bookId:$chapter:$paragraph:$playing:$loading"
        if (key != lastNotifiedKey) {
            lastNotifiedKey = key
            updateSession()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (bookId != null) nm.notify(NOTIF_ID, buildNotification())
        }
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
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, book?.chapterTitle(chapter).orEmpty())
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author.ifEmpty { bookTitle })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, bookTitle)
        coverBitmap?.let { meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(meta.build())
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

    private fun openPlayerIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(bookTitle.ifEmpty { getString(R.string.app_name) })
            .setContentText(book?.chapterTitle(chapter) ?: getString(R.string.preparing))
            .setLargeIcon(coverBitmap)
            .setContentIntent(openPlayerIntent())
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
}
