package com.pilchard.player

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.Gravity
import android.widget.FrameLayout
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The Cinematic player for movies and shows. Jellyfin hands us one video;
 * when we close, we report the position back so Jellyfin can resume or mark it watched.
 */
class PlayerActivity : AppCompatActivity(), MpvController.Listener, SeekBarView.Listener, LiveTvController.Host {

    private lateinit var mpv: MpvController
    private var reporter: JellyfinReporter? = null
    // Play Store build only: puts back the resume point if Jellyfin's TV app wipes it.
    private var resumeFixer: ResumeFixer? = null
    private lateinit var audioSettings: AudioSettings
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (started && !finished) reporter?.progress(positionSec * 1000, paused)
            handler.postDelayed(this, 10_000)
        }
    }

    private lateinit var overlay: View
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var timeView: TextView
    private lateinit var seekBar: SeekBarView
    private lateinit var playPause: Button
    private lateinit var chaptersButton: Button
    private lateinit var tracksButton: Button
    private lateinit var panel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelList: LinearLayout
    private lateinit var loading: View
    private lateinit var subtitleView: TextView
    private lateinit var surfaceView: SurfaceView

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private lateinit var loadStatus: TextView

    /** Shows how a starting stream is doing, so a long wait can be told apart: no answer yet vs. data arriving. */
    private val loadStatusRunnable = object : Runnable {
        override fun run() {
            if (finished || !::mpv.isInitialized) return
            val secs = mpv.secondsSinceLoad()
            val text = when (mpv.startStage()) {
                MpvController.StartStage.PLAYING -> null
                MpvController.StartStage.CONNECTING -> getString(R.string.load_connecting, secs)
                MpvController.StartStage.WAITING_FOR_PICTURE -> getString(R.string.load_waiting_picture, secs)
            }
            // Only for waits longer than a moment.
            if (text != null && secs >= 3) {
                loadStatus.text = text
                loadStatus.visibility = View.VISIBLE
            } else {
                loadStatus.visibility = View.GONE
            }
            handler.postDelayed(this, 1000)
        }
    }

    private var positionSec = 0L
    private var durationSec = 0L
    private var paused = false
    private var chapterIndex = -1L
    private var chapters: List<Chapter> = emptyList()
    private var started = false
    private var finished = false

    // Live TV (set once Jellyfin's link turns out to be a TV channel)
    private var live: LiveTvController? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoArea: Rect? = null

    override val context: Context get() = this
    private var signInHintPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerAccount.init(this)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        overlay = findViewById(R.id.overlay)
        titleView = findViewById(R.id.title)
        metaView = findViewById(R.id.meta)
        timeView = findViewById(R.id.time)
        seekBar = findViewById(R.id.seek_bar)
        playPause = findViewById(R.id.btn_play_pause)
        chaptersButton = findViewById(R.id.btn_chapters)
        tracksButton = findViewById(R.id.btn_tracks)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panel_title)
        panelList = findViewById(R.id.panel_list)
        loading = findViewById(R.id.loading)
        subtitleView = findViewById(R.id.subtitle)
        surfaceView = findViewById(R.id.surface)
        loadStatus = findViewById(R.id.load_status)

        seekBar.listener = this
        playPause.setOnClickListener { setPaused(!paused) }
        chaptersButton.setOnClickListener { openChaptersPanel() }
        tracksButton.setOnClickListener { openTracksPanel() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }

        titleView.text = intent.getStringExtra("title")
            ?: uri.lastPathSegment?.substringBeforeLast('.')
            ?: getString(R.string.app_name)

        val savedToken = ServerAccount.get(this)?.token
        reporter = JellyfinReporter.fromStreamUrl(uri.toString(), savedToken)
        if (BuildConfig.FIX_RESUME) {
            val userId = ServerAccount.get(this)?.userId
            resumeFixer = reporter?.let { r ->
                userId?.let { ResumeFixer(r.baseUrl, r.token, it, r.itemId) }
            }
            resumeFixer?.captureBefore()
        }
        audioSettings = AudioSettings(this)
        mpv = MpvController(this, this)
        mpv.audioSettings = audioSettings
        mpv.init()
        surfaceView.holder.addCallback(mpv.surfaceCallback)
        // A channel watched before starts straight from Dispatcharr (Jellyfin's link as the fallback).
        val channelId = JellyfinLink.parse(uri.toString(), savedToken)?.itemId
        val direct = channelId?.takeIf { isKnownChannel(it) }?.let { DirectStreams.get(this, it) }
        if (direct != null) {
            mpv.load(direct, 0.0, emptyList(), fallback = playableUrl(uri))
        } else {
            mpv.load(playableUrl(uri), readStartPositionMs() / 1000.0, readExternalSubs())
        }
        handler.post(loadStatusRunnable)
        // Only show the movie controls once we know it isn't a TV channel (no flash of them on live TV).
        if (!detectLiveTv(uri.toString())) showOverlay()
    }

    /**
     * Asks Jellyfin what we were given; a TV channel switches Pilchard into live TV mode.
     * Channels seen before switch right away, without waiting for Jellyfin.
     * Returns true while the answer is still pending (the movie controls wait for it).
     */
    private fun detectLiveTv(url: String): Boolean {
        val link = JellyfinLink.parse(url, ServerAccount.get(this)?.token) ?: return false
        ServerAccount.rememberServer(this, link.baseUrl)
        if (link.token == null) {
            // Not signed in: check after loading whether this is a live stream and explain.
            signInHintPending = true
            return false
        }
        val api = JellyfinApi(link)
        if (isKnownChannel(link.itemId)) {
            startLiveTv(api, link)
            return true
        }
        detecting = true
        api.async({ itemType(link.itemId) }) { type ->
            detecting = false
            if (finished || live != null) return@async
            if (type == "TvChannel") {
                rememberChannel(link.itemId)
                startLiveTv(api, link)
            } else {
                showOverlay()
            }
        }
        return true
    }

    private fun startLiveTv(api: JellyfinApi, link: JellyfinLink) {
        reporter = null
        resumeFixer = null
        // Live TV stays at the TV's normal refresh rate (no dark screen while the TV switches).
        FrameRateMatcher.reset(this)
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(hideOverlayRunnable)
        overlay.visibility = View.GONE
        live = LiveTvController(this, findViewById(R.id.player_root), mpv, api, link, link.itemId)
    }

    /** True while waiting to hear from Jellyfin whether this is a TV channel. */
    private var detecting = false

    private fun knownChannels(): Set<String> =
        getSharedPreferences("live", MODE_PRIVATE).getStringSet("known_channels", emptySet()) ?: emptySet()

    private fun isKnownChannel(id: String) = id in knownChannels()

    private fun rememberChannel(id: String) {
        getSharedPreferences("live", MODE_PRIVATE).edit()
            .putStringSet("known_channels", knownChannels() + id).apply()
    }

    // ----- LiveTvController.Host -----

    override fun insertView(view: View) {
        val root = findViewById<FrameLayout>(R.id.player_root)
        root.addView(view, root.indexOfChild(panel))
    }

    override fun setVideoArea(area: Rect?) {
        videoArea = area
        if (videoWidth > 0 && videoHeight > 0) onVideoSize(videoWidth, videoHeight)
    }

    // ----- Reading what Jellyfin sent -----

    private fun playableUrl(uri: Uri): String {
        if (uri.scheme == "content") {
            contentResolver.openFileDescriptor(uri, "r")?.let { return "fd://${it.detachFd()}" }
        }
        return uri.toString()
    }

    /** Resume point in milliseconds (MX Player and VLC style extras). */
    private fun readStartPositionMs(): Long {
        val extras = intent.extras ?: return 0
        for (key in listOf("position", "extra_position")) {
            @Suppress("DEPRECATION")
            val value = extras.get(key)
            if (value is Number) return value.toLong()
        }
        return 0
    }

    /**
     * Subtitle files Jellyfin passes along. Jellyfin's TV app sends them as text links (String[]);
     * other apps send Uri[] — read both. Links without an access token get Pilchard's.
     */
    private fun readExternalSubs(): List<MpvController.ExternalSub> {
        @Suppress("DEPRECATION")
        val raw = intent.extras?.get("subs")
        val links: List<String> = when (raw) {
            is Array<*> -> raw.mapNotNull { (it as? Uri)?.toString() ?: (it as? String) }
            is List<*> -> raw.mapNotNull { (it as? Uri)?.toString() ?: (it as? String) }
            else -> emptyList()
        }
        val names = intent.getStringArrayExtra("subs.name")
        val base = intent.data?.let { JellyfinLink.parse(it.toString(), null)?.baseUrl }
        val token = ServerAccount.get(this)?.token
        return links.mapIndexedNotNull { i, link ->
            var url = link.trim().ifEmpty { return@mapIndexedNotNull null }
            if (url.startsWith("/") && base != null) url = base + url
            if (token != null && url.startsWith("http") && !url.contains("api_key=", ignoreCase = true)) {
                url += (if ('?' in url) "&" else "?") + "api_key=" + Uri.encode(token)
            }
            MpvController.ExternalSub(url, names?.getOrNull(i))
        }
    }

    // ----- Overlay -----

    private fun showOverlay(focus: View = playPause) {
        live?.let { it.showOverlay(); return }
        overlay.visibility = View.VISIBLE
        if (!focus.isFocused) focus.requestFocus()
        scheduleHide()
    }

    private fun hideOverlay() {
        if (panel.visibility == View.VISIBLE || seekBar.isEditing || paused) return
        overlay.visibility = View.GONE
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, 5000)
    }

    private fun setPaused(p: Boolean) {
        mpv.setPaused(p)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Volume buttons go straight to Android, so the remote controls the TV's volume.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_VOLUME_MUTE
        ) return super.dispatchKeyEvent(event)
        // Closing: ignore the remote so nothing talks to the player while it shuts down.
        if (finished) return true

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { setPaused(!paused); showOverlay(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { setPaused(false); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { setPaused(true); showOverlay(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { mpv.seekBy(30); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { mpv.seekBy(-10); return true }
            }
            live?.let { lv ->
                if (panel.visibility != View.VISIBLE && lv.handleKey(event)) return true
                return super.dispatchKeyEvent(event)
            }
            // Still finding out whether this is a TV channel: wait (Back still works).
            if (detecting && code != KeyEvent.KEYCODE_BACK) return true
            if (overlay.visibility != View.VISIBLE) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        showOverlay(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        showOverlay(seekBar)
                        seekBar.step(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, event.repeatCount)
                        return true
                    }
                }
            }
            scheduleHide()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onBack() {
        when {
            panel.visibility == View.VISIBLE -> closePanel()
            live?.onBack() == true -> Unit
            live != null -> finishWithResult(ended = false)
            seekBar.isEditing -> { seekBar.cancel(); playPause.requestFocus() }
            overlay.visibility == View.VISIBLE && !paused -> overlay.visibility = View.GONE
            else -> finishWithResult(ended = false)
        }
    }

    // ----- Side panel (audio, subtitles, chapters) -----

    override fun openTracksPanel() {
        panelTitle.setText(R.string.audio_and_subtitles)
        panelList.removeAllViews()
        val tracks = mpv.tracks()

        // What's playing, for troubleshooting.
        mpv.streamInfo().takeIf { it.isNotBlank() }?.let { info ->
            addHeader(getString(R.string.stream_info))
            panelList.addView(TextView(this).apply {
                text = info.replace("  ·  ", "\n")
                setTextColor(getColor(R.color.text_muted))
                textSize = 13f
                setPadding((14 * resources.displayMetrics.density).toInt(), 0, 0, (6 * resources.displayMetrics.density).toInt())
            })
        }

        addHeader(getString(R.string.sound))
        addBoostRow()
        addRow(getString(R.string.voice_boost), getString(R.string.voice_boost_detail), audioSettings.voiceBoost) {
            audioSettings.voiceBoost = !audioSettings.voiceBoost
            mpv.applyAudioEffects()
            refreshPanel { openTracksPanel() }
        }
        addRow(getString(R.string.night_mode), getString(R.string.night_mode_detail), audioSettings.nightMode) {
            audioSettings.nightMode = !audioSettings.nightMode
            mpv.applyAudioEffects()
            refreshPanel { openTracksPanel() }
        }
        // Movies only: live TV always decodes the sound.
        if (live == null) {
            addRow(getString(R.string.passthrough), getString(R.string.passthrough_detail), audioSettings.passthrough) {
                audioSettings.passthrough = !audioSettings.passthrough
                mpv.applyAudioEffects()
                refreshPanel { openTracksPanel() }
            }
        }

        addHeader(getString(R.string.audio))
        tracks.filter { it.type == "audio" }.forEach { t ->
            addRow(trackLabel(t), trackDetail(t), t.isSelected) {
                mpv.selectAudio(t.id); refreshPanel { openTracksPanel() }
            }
        }

        addHeader(getString(R.string.subtitles))
        val subs = tracks.filter { it.type == "sub" }
        addRow(getString(R.string.off), null, subs.none { it.isSelected }) {
            mpv.selectSub(null); refreshPanel { openTracksPanel() }
        }
        subs.forEach { t ->
            addRow(trackLabel(t), trackDetail(t), t.isSelected) {
                mpv.selectSub(t.id); refreshPanel { openTracksPanel() }
            }
        }
        showPanel()
    }

    private fun openChaptersPanel() {
        panelTitle.setText(R.string.chapters)
        panelList.removeAllViews()
        chapters.forEachIndexed { i, c ->
            val name = c.title?.takeIf { it.isNotBlank() } ?: getString(R.string.chapter_n, i + 1)
            addRow(name, formatTime(c.timeSec.toLong()), i.toLong() == chapterIndex) {
                mpv.jumpToChapter(i)
                closePanel()
            }
        }
        showPanel()
    }

    private fun refreshPanel(reopen: () -> Unit) {
        val focusedIndex = (0 until panelList.childCount).firstOrNull { panelList.getChildAt(it).isFocused }
        // mpv updates the selection a moment later; wait briefly before redrawing.
        handler.postDelayed({
            reopen()
            focusedIndex?.let { panelList.getChildAt(it)?.requestFocus() }
        }, 150)
    }

    private fun showPanel() {
        panel.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlayRunnable)
        val selected = (0 until panelList.childCount).map { panelList.getChildAt(it) }
            .firstOrNull { it.isFocusable && it.isSelected }
            ?: (0 until panelList.childCount).map { panelList.getChildAt(it) }.firstOrNull { it.isFocusable }
        selected?.requestFocus()
    }

    override fun showSchedule(title: String, rows: List<Pair<String, String>>, onPlay: () -> Unit) {
        panelTitle.text = title
        panelList.removeAllViews()
        addHeader(getString(R.string.schedule_until_midnight))
        if (rows.isEmpty()) {
            addRow(getString(R.string.no_info), null, false) {}
        } else {
            rows.forEachIndexed { i, (name, time) ->
                // The program on now is marked; picking any row tunes to the channel.
                addRow(name, time, i == 0 && time.startsWith(getString(R.string.on_now, "").trim())) {
                    panel.visibility = View.GONE
                    onPlay()
                }
            }
        }
        showPanel()
    }

    private fun closePanel() {
        val wasTracks = panelTitle.text == getString(R.string.audio_and_subtitles)
        panel.visibility = View.GONE
        live?.let { it.onPanelClosed(); return }
        (if (wasTracks) tracksButton else chaptersButton).requestFocus()
        scheduleHide()
    }

    private fun addHeader(text: String) {
        val tv = layoutInflater.inflate(R.layout.panel_header, panelList, false) as TextView
        tv.text = text
        panelList.addView(tv)
    }

    /** Volume boost row: Left / Right change it in 2 dB steps, OK steps up and wraps back to off. */
    private fun addBoostRow() {
        fun label() = if (audioSettings.boostDb == 0) getString(R.string.boost_off)
        else getString(R.string.boost_db, audioSettings.boostDb)

        val row = addRow(getString(R.string.volume_boost), label(), audioSettings.boostDb > 0) {
            val next = audioSettings.boostDb + AudioSettings.BOOST_STEP_DB
            audioSettings.boostDb = if (next > AudioSettings.MAX_BOOST_DB) 0 else next
            mpv.applyAudioEffects()
        }
        val detail = row.findViewById<TextView>(R.id.row_detail)
        val check = row.findViewById<View>(R.id.row_check)
        fun refresh() {
            detail.text = label()
            check.visibility = if (audioSettings.boostDb > 0) View.VISIBLE else View.INVISIBLE
        }
        row.setOnClickListener {
            val next = audioSettings.boostDb + AudioSettings.BOOST_STEP_DB
            audioSettings.boostDb = if (next > AudioSettings.MAX_BOOST_DB) 0 else next
            mpv.applyAudioEffects()
            refresh()
        }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> AudioSettings.BOOST_STEP_DB
                KeyEvent.KEYCODE_DPAD_LEFT -> -AudioSettings.BOOST_STEP_DB
                else -> return@setOnKeyListener false
            }
            audioSettings.boostDb = audioSettings.boostDb + step
            mpv.applyAudioEffects()
            refresh()
            true
        }
    }

    private fun addRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit): View {
        val row = layoutInflater.inflate(R.layout.panel_row, panelList, false)
        row.findViewById<TextView>(R.id.row_label).apply {
            text = label
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        row.findViewById<TextView>(R.id.row_detail).apply {
            text = detail ?: ""
            visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        row.findViewById<View>(R.id.row_check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        row.isSelected = selected
        row.setOnClickListener { onClick() }
        panelList.addView(row)
        return row
    }

    private fun trackLabel(t: Track): String {
        val lang = LanguagePicker.languageName(t.lang) ?: getString(R.string.language_unknown)
        val title = t.title?.takeIf { it.isNotBlank() && !it.equals(lang, ignoreCase = true) }
        return if (title != null) "$lang · $title" else lang
    }

    private fun trackDetail(t: Track): String {
        val parts = mutableListOf<String>()
        t.codec?.let { parts += it.uppercase() }
        t.channels?.let { parts += channelName(it) }
        if (t.isForced) parts += getString(R.string.forced)
        if (t.isHearingImpaired) parts += "SDH"
        if (t.isExternal) parts += getString(R.string.external)
        return parts.joinToString(" · ")
    }

    private fun channelName(n: Int) = when (n) {
        1 -> getString(R.string.channels_mono); 2 -> getString(R.string.channels_stereo); 6 -> "5.1"; 8 -> "7.1"; else -> "${n}ch"
    }

    // ----- Seek bar -----

    override fun onSeekPreview(sec: Long) {
        timeView.text = getString(R.string.time_of, formatTime(sec), formatTime(durationSec))
        metaView.setText(R.string.seek_hint)
        handler.removeCallbacks(hideOverlayRunnable)
    }

    override fun onSeekCommit(sec: Long) {
        mpv.seekTo(sec)
        positionSec = sec
        reporter?.progress(sec * 1000, paused)
        seekBar.positionSec = sec
        playPause.requestFocus()
        updateMeta()
        scheduleHide()
    }

    override fun onSeekCancel() {
        updateTime()
        updateMeta()
    }

    // ----- mpv events -----

    override fun onPosition(sec: Long) {
        live?.onPosition(sec)
        positionSec = sec
        seekBar.positionSec = sec
        if (!started) { started = true; loading.visibility = View.GONE }
        if (!seekBar.isEditing) updateTime()
    }

    override fun onDuration(sec: Long) {
        durationSec = sec
        seekBar.durationSec = sec
        updateTime()
    }

    override fun onPauseChanged(paused: Boolean) {
        if (started && paused != this.paused) {
            reporter?.progress(positionSec * 1000, paused, if (paused) "pause" else "unpause")
        }
        this.paused = paused
        live?.let { it.onPauseChanged(paused); return }
        playPause.setText(if (paused) R.string.play else R.string.pause)
        playPause.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (paused) R.drawable.ic_play else R.drawable.ic_pause, 0, 0, 0
        )
        if (paused) showOverlay(currentFocus ?: playPause) else scheduleHide()
    }

    override fun onBuffering(buffering: Boolean) {
        loading.visibility = if (buffering || !started) View.VISIBLE else View.GONE
        live?.onBuffering(buffering)
    }

    override fun onChapterChanged(index: Long) {
        chapterIndex = index
        updateMeta()
    }

    override fun onFileLoaded() {
        if (finished) return
        if (signInHintPending) {
            signInHintPending = false
            if (mpv.looksLive()) {
                android.widget.Toast.makeText(this, R.string.sign_in_for_live, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        // Movies and shows: match the TV's refresh rate to the video (smooth motion).
        // Live TV: never switch — each switch blanks the TV for a few seconds, which looks like it
        // turned off. For smooth 59.94 channels, set the Shield itself to 59.940 Hz.
        val isLive = live != null || mpv.looksLive()
        if (!isLive) FrameRateMatcher.match(this, mpv.containerFps())
        reporter?.started(readStartPositionMs())
        handler.postDelayed(progressRunnable, 10_000)
        chapters = mpv.chapters()
        seekBar.chapterTimes = chapters.map { it.timeSec }
        chaptersButton.visibility = if (chapters.size > 1) View.VISIBLE else View.GONE
        updateMeta()
    }

    override fun onEndReached() {
        live?.let { it.onStreamEnded(); return }
        finishWithResult(ended = true)
    }

    override fun onSubtitleText(text: String) {
        // Plain text only: strip any leftover formatting tags.
        val clean = text.replace(Regex("<[^>]*>"), "").replace(Regex("\\{[^}]*\\}"), "").trim()
        subtitleView.text = clean
        subtitleView.visibility = if (mpv.directMode && clean.isNotEmpty()) View.VISIBLE else View.GONE
    }

    override fun onRenderModeChanged(direct: Boolean) {
        if (!direct) subtitleView.visibility = View.GONE
    }

    /** Sizes the video to fit the screen without stretching (direct mode can't letterbox on its own). */
    override fun onVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        val root = surfaceView.parent as View
        val area = videoArea ?: Rect(0, 0, root.width, root.height)
        val screenW = area.width()
        val screenH = area.height()
        if (screenW <= 0 || screenH <= 0) {
            root.post { onVideoSize(width, height) }
            return
        }
        val videoAspect = width.toFloat() / height
        val screenAspect = screenW.toFloat() / screenH
        val (w, h) = if (videoAspect > screenAspect) {
            screenW to (screenW / videoAspect).toInt()
        } else {
            (screenH * videoAspect).toInt() to screenH
        }
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        lp.height = h
        if (videoArea == null) {
            lp.gravity = Gravity.CENTER
            lp.leftMargin = 0
            lp.topMargin = 0
        } else {
            // Centered inside the given area (live TV channel blocks: video on the right).
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = area.left + (screenW - w) / 2
            lp.topMargin = area.top + (screenH - h) / 2
        }
        surfaceView.layoutParams = lp
    }

    private fun updateTime() {
        timeView.text = getString(R.string.time_of, formatTime(positionSec), formatTime(durationSec))
    }

    private fun updateMeta() {
        if (seekBar.isEditing) return
        metaView.text = if (chapters.size > 1 && chapterIndex >= 0) {
            getString(R.string.chapter_of, chapterIndex + 1, chapters.size)
        } else ""
        metaView.visibility = if (metaView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun formatTime(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // ----- Closing -----

    /**
     * Tells Jellyfin where we stopped, using mpv-android's result format:
     *  - stopped early: "position" (milliseconds, Int) is included, so Jellyfin saves a resume point
     *  - watched to the end: "position" is left out, which Jellyfin reads as "finished"
     */
    private fun finishWithResult(ended: Boolean) {
        if (finished) return
        finished = true
        handler.removeCallbacks(progressRunnable)
        // Save the spot on the server directly; at the end, report the full length so it counts as watched.
        reporter?.stopped(if (ended) durationSec * 1000 else positionSec * 1000)
        if (!ended && live == null) resumeFixer?.guard(positionSec * 1000, durationSec * 1000)
        val result = Intent().apply {
            data = intent.data
            if (!ended) {
                putExtra("position", (positionSec * 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            }
            putExtra("duration", (durationSec * 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }
        setResult(RESULT_OK, result)

        // Stop the video and drop the HDR picture first, hand the TV back its normal mode,
        // then close on a plain black screen with no animation. This avoids the frozen frame
        // with the HDR logo, and keeps the TV to a single mode switch on the way out.
        mpv.releaseVideo()
        surfaceView.visibility = View.GONE
        subtitleView.visibility = View.GONE
        overlay.visibility = View.GONE
        panel.visibility = View.GONE
        loading.visibility = View.GONE
        loadStatus.visibility = View.GONE
        FrameRateMatcher.reset(this)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        if (isFinishing || !hasWindowFocus()) {
            closeNow()
        } else {
            // A short pause lets the video hardware let go before the window closes.
            handler.postDelayed({ closeNow() }, 150)
        }
    }

    private fun closeNow() {
        if (isFinishing) return
        finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving with the Home button still reports the position back.
        if (!isChangingConfigurations && !isFinishing) finishWithResult(ended = false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        live?.release()
        if (::mpv.isInitialized) {
            surfaceView.holder.removeCallback(mpv.surfaceCallback)
            mpv.destroy()
        }
        super.onDestroy()
    }
}
