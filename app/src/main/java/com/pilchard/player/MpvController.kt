package com.pilchard.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import dev.jdtech.mpv.MPVLib

/** One audio, video or subtitle track as mpv reports it. */
data class Track(
    val id: Int,
    val type: String,
    val lang: String?,
    val title: String?,
    val codec: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isSelected: Boolean,
    val isExternal: Boolean,
    val isHearingImpaired: Boolean,
    val channels: Int?,
)

data class Chapter(val title: String?, val timeSec: Double)

/**
 * Everything that talks to libmpv lives in this one class, so the rest of the app
 * never touches the mpv API directly.
 */
class MpvController(
    private val context: Context,
    private val listener: Listener,
) : MPVLib.EventObserver {

    interface Listener {
        fun onPosition(sec: Long)
        fun onDuration(sec: Long)
        fun onPauseChanged(paused: Boolean)
        fun onBuffering(buffering: Boolean)
        fun onChapterChanged(index: Long)
        fun onFileLoaded()
        fun onEndReached()
        fun onSubtitleText(text: String)
        fun onVideoSize(width: Int, height: Int)
        fun onRenderModeChanged(direct: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var lib: MPVLib
    // Read from mpv's thread too, so every thread sees the latest value.
    @Volatile private var initialized = false
    private var pendingSubs: List<ExternalSub> = emptyList()

    /**
     * Direct mode: the Shield's video hardware draws the picture straight to the screen
     * (no copying, real HDR to the TV). Smooth even for 4K HDR, but mpv can't draw subtitles
     * on top, so Pilchard draws text subtitles itself. Picture-based subtitles (PGS, DVD)
     * can't be drawn that way, so for those Pilchard switches to GPU mode, where mpv draws everything.
     */
    var directMode = true
        private set
    var audioSettings: AudioSettings? = null
    private var audioLayout: String? = null

    /** Rebuilds the audio filters (boost, night mode, voice boost) from the saved settings. */
    fun applyAudioEffects() {
        if (!initialized) return
        val settings = audioSettings ?: return
        // Surround passthrough when no effect is on; decode (so the effects work) when one is.
        // Live TV always decodes (no passthrough there).
        val spdif = if (settings.passthrough && !settings.liveTv && !settings.anyEffect) "ac3,eac3" else ""
        val switching = spdif != currentSpdif
        currentSpdif = spdif
        // Switching between passthrough and decoding mid-stream: stop the sound track first, change
        // the mode and effects, then start the track again. Changing them while the sound is
        // playing (effects on top of passthrough) is what made the sound drop out.
        val aid = if (switching) lib.getPropertyString("aid")?.takeIf { it != "no" && it != "auto" } else null
        if (aid != null) lib.setPropertyString("aid", "no")
        if (switching) lib.setPropertyString("audio-spdif", spdif)
        // Volume first: it works even if the filters below can't be built.
        lib.setPropertyString("volume", AudioEffects.volumePercent(settings.boostDb))
        lib.setPropertyString("af", AudioEffects.filterChain(settings, audioLayout))
        if (aid != null) lib.setPropertyString("aid", aid)
    }

    private var currentSpdif: String? = null

    private val voName get() = if (directMode) "mediacodec_embed" else "gpu"
    private val hwdecName get() = if (directMode) "mediacodec" else "mediacodec-copy"

    data class ExternalSub(val url: String, val name: String?)

    fun init() {
        lib = MPVLib.create(context.applicationContext) ?: error("Could not start the mpv engine")

        // Video: hardware decoding, drawn directly (or by the GPU when needed, see directMode).
        // No video output until the screen surface exists. Starting direct mode without a surface
        // crashes mpv ("WinID" assertion) — which happened when a file opened faster than the screen.
        lib.setOptionString("vo", "null")
        lib.setOptionString("gpu-context", "android")
        lib.setOptionString("opengl-es", "yes")
        lib.setOptionString("hwdec", hwdecName)
        lib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")

        // Audio output on Android.
        lib.setOptionString("ao", "audiotrack,opensles")
        // Lets the volume boost go above 100% (the remote's volume still controls the TV).
        lib.setOptionString("volume-max", "300")

        // Streaming cache sized for the Shield's memory.
        lib.setOptionString("cache", "yes")
        lib.setOptionString("demuxer-max-bytes", "64MiB")
        lib.setOptionString("demuxer-max-back-bytes", "32MiB")

        // English audio and subtitles by default, for everything.
        lib.setOptionString("alang", "en,eng,english")
        lib.setOptionString("slang", "en,eng,english")
        lib.setOptionString("subs-with-matching-audio", "yes")
        lib.setOptionString("subs-fallback", "no")

        // Stay on the last frame at the end so we can report "finished" cleanly.
        lib.setOptionString("keep-open", "yes")
        lib.setOptionString("input-default-bindings", "no")
        lib.setOptionString("force-window", "no")
        lib.setOptionString("idle", "yes")

        // Tell servers which app is asking (mpv's default is "libmpv").
        lib.setOptionString("user-agent", "Pilchard Player/${BuildConfig.VERSION_NAME} (Android TV)")
        lib.init()
        lib.addObserver(this)

        lib.observeProperty("time-pos", FMT_INT64)
        lib.observeProperty("duration", FMT_INT64)
        lib.observeProperty("pause", FMT_FLAG)
        lib.observeProperty("paused-for-cache", FMT_FLAG)
        lib.observeProperty("eof-reached", FMT_FLAG)
        lib.observeProperty("chapter", FMT_INT64)
        lib.observeProperty("sub-text", FMT_STRING)
        lib.observeProperty("audio-params/channels", FMT_STRING)
        initialized = true
    }

    /** True while the screen surface is attached; mpv may only use direct mode then. */
    @Volatile private var surfaceAttached = false
    /** A load waiting for the surface. */
    private var pendingStart = false

    val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (!initialized) return
            lib.attachSurface(holder.surface)
            lib.setOptionString("force-window", "yes")
            lib.setPropertyString("vo", voName)
            surfaceAttached = true
            // A video asked for before the screen was ready starts now.
            if (pendingStart) {
                pendingStart = false
                currentUrl?.let { startFile(it, currentStart) }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (!initialized) return
            lib.setPropertyString("android-surface-size", "${width}x$height")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (!initialized) return
            surfaceAttached = false
            lib.setPropertyString("vo", "null")
            lib.setOptionString("force-window", "no")
            lib.detachSurface()
        }
    }

    // ----- Stall watchdog -----
    // Some IPTV streams send nothing on the first request and only start on the second
    // (the source wakes up). If nothing plays within a few seconds, Pilchard asks again by itself.
    private var currentUrl: String? = null
    private var currentStart = 0.0
    private var retries = 0
    @Volatile private var playingSinceLoad = false
    /** The stream answered and mpv recognized it (data is arriving), but there may be no picture yet. */
    @Volatile private var openedSinceLoad = false
    private var loadStartedAt = 0L

    /** Where the latest load is, for the on-screen status while a stream starts. */
    enum class StartStage { CONNECTING, WAITING_FOR_PICTURE, PLAYING }

    fun startStage(): StartStage = when {
        playingSinceLoad -> StartStage.PLAYING
        openedSinceLoad -> StartStage.WAITING_FOR_PICTURE
        else -> StartStage.CONNECTING
    }

    /** Seconds since the latest stream was asked for. */
    fun secondsSinceLoad(): Long = (android.os.SystemClock.elapsedRealtime() - loadStartedAt) / 1000
    private val watchdog = Runnable { checkStarted() }

    /** How the current video is reached, shown in the stream info. */
    private enum class Route { NORMAL, DIRECT, FELL_BACK }
    @Volatile private var route = Route.NORMAL

    /** Where to go if [currentUrl] doesn't answer (the Jellyfin route, when playing a channel directly). */
    private var fallbackUrl: String? = null

    /** The direct link failed: switch to the fallback right away. */
    private fun useFallback() {
        val fb = fallbackUrl ?: return
        fallbackUrl = null
        route = Route.FELL_BACK
        currentUrl = fb
        startFile(fb, currentStart)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, RETRY_AFTER_MS)
    }

    /**
     * Live TV mode (set by [setLiveCache]). Only live streams are restarted when they don't start:
     * a stuck channel needs a fresh start, but a movie on a slow server is just slow, and starting
     * over would throw away what was already loaded (it never got to play on a server taking 15+ s).
     */
    @Volatile private var liveMode = false

    private fun checkStarted() {
        // Data is already arriving (slow, low-bitrate streams): asking again would only start over.
        if (!initialized || playingSinceLoad || openedSinceLoad) return
        // Movies and shows: keep waiting. Check again later in case this turns out to be a channel.
        if (!liveMode) { main.postDelayed(watchdog, RETRY_AFTER_MS); return }
        if (fallbackUrl != null) { useFallback(); return }
        val url = currentUrl ?: return
        if (retries >= MAX_RETRIES) return
        retries++
        startFile(url, currentStart)
        main.postDelayed(watchdog, RETRY_AFTER_MS + retries * 3000L)
    }

    /**
     * True from asking mpv for a file until mpv says it started it. An "end-file" in that window
     * belongs to the previous file (the channel being left), not to the one just asked for.
     */
    @Volatile private var awaitingStart = false

    private fun startFile(url: String, startSec: Double) {
        if (!surfaceAttached) {
            Log.i(TAG, "Waiting for the screen before starting ${redact(url)}")
            pendingStart = true; return
        }
        Log.i(TAG, "loadfile start=$startSec ${redact(url)}")
        awaitingStart = true
        lib.setPropertyString(
            "start",
            if (startSec > 1.0) "%.3f".format(java.util.Locale.US, startSec) else "none"
        )
        lib.command(arrayOf("loadfile", url))
    }

    fun load(url: String, startSec: Double, externalSubs: List<ExternalSub>, fallback: String? = null) {
        if (!initialized) return
        currentUrl = url
        fallbackUrl = fallback
        route = if (fallback != null) Route.DIRECT else Route.NORMAL
        currentStart = startSec
        retries = 0
        playingSinceLoad = false
        openedSinceLoad = false
        loadStartedAt = android.os.SystemClock.elapsedRealtime()
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, RETRY_AFTER_MS)
        pendingSubs = externalSubs
        // The new stream may have a different channel layout (stereo / 5.1); learn it again.
        audioLayout = null
        startFile(url, startSec)
    }

    /**
     * Live TV channel change: turn the volume boost, night mode and voice boost off and rebuild
     * the sound settings before the new channel loads, so its audio always starts.
     */
    fun resetBoostForNewChannel() {
        if (!initialized) return
        audioSettings?.let { it.boostDb = 0; it.nightMode = false; it.voiceBoost = false }
        audioLayout = null
        applyAudioEffects()
    }

    /** Switches to live TV's own sound settings (starting with everything off). */
    fun useLiveTvAudio() {
        val settings = audioSettings ?: return
        settings.liveTv = true
        settings.boostDb = 0
        settings.nightMode = false
        settings.voiceBoost = false
        applyAudioEffects()
    }

    /** Live TV: keep only a small buffer (about a minute for typical channels) to save memory. */
    fun setLiveCache() {
        liveMode = true
        lib.setPropertyString("demuxer-max-bytes", "48MiB")
        lib.setPropertyString("demuxer-max-back-bytes", "8MiB")
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        lib.setPropertyString("pause", if (paused) "yes" else "no")
    }

    fun seekTo(sec: Long) = lib.command(arrayOf("seek", sec.toString(), "absolute"))

    fun seekBy(sec: Long) = lib.command(arrayOf("seek", sec.toString(), "relative"))

    fun selectAudio(id: Int) = lib.setPropertyString("aid", id.toString())

    fun selectSub(id: Int?) {
        lib.setPropertyString("sid", id?.toString() ?: "no")
        val chosen = id?.let { wanted -> tracks().firstOrNull { it.type == "sub" && it.id == wanted } }
        setDirectMode(chosen == null || !isPictureSubtitle(chosen))
    }

    private fun isPictureSubtitle(t: Track): Boolean {
        val codec = t.codec?.lowercase() ?: return false
        return "pgs" in codec || "dvd_sub" in codec || "dvdsub" in codec || "dvb_sub" in codec ||
            "dvbsub" in codec || "xsub" in codec || "vobsub" in codec
    }

    private fun setDirectMode(on: Boolean) {
        if (on == directMode) return
        directMode = on
        lib.setPropertyString("hwdec", hwdecName)
        if (surfaceAttached) lib.setPropertyString("vo", voName)
        post { listener.onRenderModeChanged(on) }
    }

    fun jumpToChapter(index: Int) = lib.setPropertyString("chapter", index.toString())

    private fun looksLiveSafe(): Boolean = try { looksLive() } catch (_: Exception) { false }

    /** True for streams with no end and no seeking, like live TV. */
    fun looksLive(): Boolean =
        lib.getPropertyString("seekable") == "no" || (lib.getPropertyDouble("duration") ?: 0.0) <= 0.0

    /**
     * One line describing what's playing, for troubleshooting, e.g.
     * "H264 · 1280×720 · 29.97 fps · Progressive · HW decode · AAC 2.0 · 48 kHz · 3.5 Mb/s".
     * Anything mpv can't tell (yet) is left out or shown with a "?".
     */
    fun streamInfo(): String {
        if (!initialized) return ""
        fun str(name: String) = lib.getPropertyString(name)?.takeIf { it.isNotBlank() }
        fun num(name: String) = str(name)?.toDoubleOrNull()
        val parts = mutableListOf<String>()

        str("video-format")?.let { parts += it.uppercase() }
        val w = num("width")?.toInt() ?: num("video-params/w")?.toInt()
        val h = num("height")?.toInt() ?: num("video-params/h")?.toInt()
        if (w != null && h != null && w > 0) parts += "${w}×$h"
        (num("container-fps") ?: num("estimated-vf-fps"))?.let { parts += "${FrameRateMatcher.label(it)} fps" }
        parts += when (str("video-frame-info/interlaced")) {
            "yes" -> context.getString(if (str("video-frame-info/tff") == "yes") R.string.info_interlaced_tff else R.string.info_interlaced)
            "no" -> context.getString(R.string.info_progressive)
            else -> context.getString(R.string.info_scan_unknown)
        }
        when (str("video-params/gamma")) {
            "pq" -> parts += "HDR10"
            "hlg" -> parts += "HLG"
        }
        val hw = str("hwdec-current")
        parts += context.getString(if (hw == null || hw == "no") R.string.info_sw_decode else R.string.info_hw_decode)

        str("audio-codec-name")?.let { codec ->
            // Channel count from the track itself (passthrough reports the wrapped format instead).
            val ch = tracks().firstOrNull { it.type == "audio" && it.isSelected }?.channels
                ?: num("audio-params/channel-count")?.toInt()
            val layout = when (ch) { null -> ""; 1 -> " 1.0"; 2 -> " 2.0"; 6 -> " 5.1"; 8 -> " 7.1"; else -> " ${ch}ch" }
            parts += codec.uppercase() + layout
            if (str("audio-params/format")?.startsWith("spdif") == true) parts += context.getString(R.string.info_passthrough)
        }
        num("audio-params/samplerate")?.let { parts += "${(it / 1000).let { k -> if (k % 1.0 == 0.0) k.toInt().toString() else "%.1f".format(java.util.Locale.US, k) }} kHz" }
        val bits = (num("video-bitrate") ?: 0.0) + (num("audio-bitrate") ?: 0.0)
        if (bits > 0) parts += "%.1f Mb/s".format(java.util.Locale.US, bits / 1_000_000)
        num("frame-drop-count")?.toInt()?.takeIf { it > 0 }?.let { parts += context.getString(R.string.info_dropped, it) }
        when (route) {
            Route.DIRECT -> parts += context.getString(R.string.info_direct_dispatcharr)
            Route.FELL_BACK -> parts += context.getString(R.string.info_via_jellyfin_fallback)
            Route.NORMAL -> if (looksLiveSafe()) parts += context.getString(R.string.info_via_jellyfin)
        }
        return parts.joinToString("  ·  ")
    }

    /** Frame rate of the video as stored in the file (e.g. 23.976). */
    fun containerFps(): Double? = lib.getPropertyDouble("container-fps")

    fun tracks(): List<Track> {
        val count = lib.getPropertyInt("track-list/count") ?: 0
        val list = mutableListOf<Track>()
        for (i in 0 until count) {
            val p = "track-list/$i"
            val type = lib.getPropertyString("$p/type") ?: continue
            val id = lib.getPropertyInt("$p/id") ?: continue
            list += Track(
                id = id,
                type = type,
                lang = lib.getPropertyString("$p/lang"),
                title = lib.getPropertyString("$p/title"),
                codec = lib.getPropertyString("$p/codec"),
                isDefault = flag("$p/default"),
                isForced = flag("$p/forced"),
                isSelected = flag("$p/selected"),
                isExternal = flag("$p/external"),
                isHearingImpaired = flag("$p/hearing-impaired"),
                channels = lib.getPropertyInt("$p/demux-channel-count"),
            )
        }
        return list
    }

    fun chapters(): List<Chapter> {
        val count = lib.getPropertyInt("chapter-list/count") ?: 0
        return (0 until count).map { i ->
            Chapter(
                title = lib.getPropertyString("chapter-list/$i/title"),
                timeSec = lib.getPropertyDouble("chapter-list/$i/time") ?: 0.0,
            )
        }
    }

    /**
     * Stops the movie and lets go of the video hardware before the screen closes.
     * This ends the HDR / Dolby Vision signal right away, so the TV doesn't hold a frozen
     * last frame (with the HDR logo) while it switches back to its normal mode.
     */
    fun releaseVideo() {
        if (!initialized) return
        main.removeCallbacks(watchdog)
        currentUrl = null
        lib.setPropertyString("pause", "yes")
        lib.command(arrayOf("stop"))
        lib.setPropertyString("vo", "null")
    }

    fun destroy() {
        if (!initialized) return
        initialized = false
        main.removeCallbacks(watchdog)
        lib.removeObserver(this)
        lib.destroy()
    }

    private fun flag(name: String) = lib.getPropertyString(name) == "yes"

    /** Adds the subtitle files Jellyfin passed along, then picks the audio and subtitles (LanguagePicker). */
    private fun onLoaded() {
        if (!initialized) return
        for (sub in pendingSubs) {
            // "English", "eng", "en", "English (SDH)", "Eng - SRT"… all count as English
            // (and the same for the TV's language in the Play Store version).
            val lang = LanguagePicker.guessCode(sub.name)
            lib.command(arrayOf("sub-add", sub.url, "auto", sub.name ?: "External", lang))
        }
        pendingSubs = emptyList()
        LanguagePicker.apply(tracks(), this)
        applyAudioEffects()
        ensureAudio()
        listener.onFileLoaded()
    }

    /**
     * Never leave a video silent: if no sound track ended up selected (no track in the preferred language,
     * or the sound settings couldn't start on this stream), pick one, without effects if needed.
     */
    private fun ensureAudio() {
        val audio = tracks().filter { it.type == "audio" }
        if (audio.isEmpty() || audio.any { it.isSelected }) return
        val pick = audio.firstOrNull { it.isDefault } ?: audio.first()
        selectAudio(pick.id)
        main.postDelayed({
            if (!initialized) return@postDelayed
            val now = tracks().filter { it.type == "audio" }
            if (now.isNotEmpty() && now.none { it.isSelected }) {
                // The sound settings stopped the audio from starting: drop them and try again.
                lib.setPropertyString("af", "")
                selectAudio(pick.id)
            }
        }, 800)
    }

    // ----- mpv callbacks (arrive on mpv's thread; hop to the main thread) -----

    /** Runs on the main thread, but only if the player hasn't been shut down in the meantime. */
    private fun post(block: () -> Unit) {
        main.post { if (initialized) block() }
    }

    override fun eventProperty(property: String) {
        if (property == "sub-text") post { listener.onSubtitleText("") }
    }

    override fun eventProperty(property: String, value: Long) {
        if (property == "time-pos" && !playingSinceLoad) {
            playingSinceLoad = true
            main.removeCallbacks(watchdog)
        }
        post {
            when (property) {
                "time-pos" -> listener.onPosition(value)
                "duration" -> listener.onDuration(value)
                "chapter" -> listener.onChapterChanged(value)
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        post {
            when (property) {
                "pause" -> listener.onPauseChanged(value)
                "paused-for-cache" -> listener.onBuffering(value)
                "eof-reached" -> if (value) listener.onEndReached()
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "sub-text" -> post { listener.onSubtitleText(value) }
            // The audio's channel layout (stereo, 5.1...) decides how Voice boost works.
            "audio-params/channels" -> post {
                if (value != audioLayout) {
                    audioLayout = value
                    applyAudioEffects()
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {}

    override fun event(eventId: Int) {
        when (eventId) {
            // A link that fails outright (nothing opened): try the fallback without waiting.
            EVENT_START_FILE -> { awaitingStart = false; Log.i(TAG, "mpv: start-file") }
            // The direct link failed outright: switch to the fallback without waiting. (An end-file
            // before our file started is the previous channel ending; ignore it.)
            EVENT_END_FILE -> if (run { Log.i(TAG, "mpv: end-file"); true }) if (!awaitingStart && !openedSinceLoad && fallbackUrl != null) {
                main.post { if (initialized) useFallback() }
            }
            EVENT_FILE_LOADED -> {
                Log.i(TAG, "mpv: file-loaded after ${secondsSinceLoad()} s")
                openedSinceLoad = true
                post { onLoaded() }
            }
            EVENT_VIDEO_RECONFIG -> {
                if (!initialized) return
                val w = lib.getPropertyInt("dwidth") ?: 0
                val h = lib.getPropertyInt("dheight") ?: 0
                if (w > 0 && h > 0) post { listener.onVideoSize(w, h) }
            }
        }
    }

    /** Hides access tokens in links before they go to the log. */
    private fun redact(text: String): String =
        text.replace(Regex("(?i)(api_key|ApiKey|Token)=([^&\\s\"]+)"), "$1=…")

    companion object {
        private const val TAG = "Pilchard"
        private val FMT_FLAG = MPVLib.MpvFormat.MPV_FORMAT_FLAG
        private val FMT_INT64 = MPVLib.MpvFormat.MPV_FORMAT_INT64
        private val FMT_STRING = MPVLib.MpvFormat.MPV_FORMAT_STRING
        private val EVENT_VIDEO_RECONFIG = MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG
        private val EVENT_FILE_LOADED = MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED
        private val EVENT_END_FILE = MPVLib.MpvEvent.MPV_EVENT_END_FILE
        private val EVENT_START_FILE = MPVLib.MpvEvent.MPV_EVENT_START_FILE

        /** No picture after this long: ask for the stream again (then a little longer each try). */
        private const val RETRY_AFTER_MS = 15_000L
        private const val MAX_RETRIES = 3
    }
}
