package com.pilchard.player

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor

/**
 * Live TV mode (the "Minimal" look). Pilchard takes over channel switching once Jellyfin
 * hands it a channel:
 *   Up / Down    channel up / channel down (shows only the channel info and clock at the top)
 *   OK / Right   show the overlay: big Guide button and recent channels along the bottom,
 *                Audio & Subtitles under the clock
 *   Left         channel blocks (Favorites, 1–100, 101–200 …) with the video still playing on the right
 *   Guide        guide on the bottom half of the screen, the channel playing on the top half
 *   Play/Pause   keeps a small buffer; "Jump to live" catches back up
 *
 * Holding OK (long press):
 *   on a recent channel     remove it from recent channels
 *   on a channel in a list  add it to / remove it from Favorites
 *   on a favorite           move it (Up / Down, then OK) or remove it from Favorites
 * Favorites are saved in Pilchard on this device, in the order you set.
 */
class LiveTvController(
    private val host: Host,
    private val root: FrameLayout,
    private val mpv: MpvController,
    private val api: JellyfinApi,
    private val link: JellyfinLink,
    startChannelId: String,
) {
    /** What the player activity provides to live TV. */
    interface Host {
        val context: Context
        fun setVideoArea(area: Rect?)
        fun openTracksPanel()
        fun insertView(view: View)
        /** Shows a channel's schedule in the side panel; picking a row plays [onPlay]. */
        fun showSchedule(title: String, rows: List<Pair<String, String>>, onPlay: () -> Unit)
    }

    private enum class Mode { NONE, OVERLAY, BLOCKS, GUIDE }

    private val ctx = host.context
    private val inflater = LayoutInflater.from(ctx)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("live", Context.MODE_PRIVATE)
    // Family version: always 12-hour. Play Store version: the TV's style (24-hour in much of Europe).
    private val timeFormat = (if (BuildConfig.FOLLOW_DEVICE_LANGUAGE) {
        if (android.text.format.DateFormat.is24HourFormat(ctx)) DateTimeFormatter.ofPattern("H:mm")
        else DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault())
    } else {
        DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)
    }).withZone(ZoneId.systemDefault())
    private val density = ctx.resources.displayMetrics.density

    private var mode = Mode.NONE
    private var channels: List<LiveChannel> = emptyList()
    private var current: LiveChannel? = null
    private var currentId: String = startChannelId
    private var nextPrograms: Map<String, List<Program>> = emptyMap()

    private var paused = false
    private var pausedAt = 0L
    private var behindMs = 0L

    // Overlay
    private val overlay = inflater.inflate(R.layout.live_overlay, root, false)
    private val badge = overlay.findViewById<TextView>(R.id.live_badge)
    private val channelText = overlay.findViewById<TextView>(R.id.live_channel)
    private val programText = overlay.findViewById<TextView>(R.id.live_program)
    private val clockText = overlay.findViewById<TextView>(R.id.live_clock)
    private val startText = overlay.findViewById<TextView>(R.id.live_start)
    private val endText = overlay.findViewById<TextView>(R.id.live_end)
    private val progress = overlay.findViewById<ProgressBar>(R.id.live_progress)
    private val jumpButton = overlay.findViewById<Button>(R.id.live_jump)
    private val guideButton = overlay.findViewById<View>(R.id.live_guide)
    private val tracksButton = overlay.findViewById<ImageButton>(R.id.live_tracks)
    private val recentsList = overlay.findViewById<LinearLayout>(R.id.live_recents)
    private val bottomBar = overlay.findViewById<View>(R.id.live_bottom)
    private val streamInfoText = overlay.findViewById<TextView>(R.id.live_stream_info)

    /**
     * True while only the channel info is showing because Up / Down changed the channel.
     * Up / Down keep surfing; OK brings up the full overlay.
     */
    private var surfing = false

    // Channel blocks / guide
    private val browser = inflater.inflate(R.layout.live_browser, root, false)
    private val blocksList = browser.findViewById<LinearLayout>(R.id.browser_blocks)
    private val channelList = browser.findViewById<LinearLayout>(R.id.browser_channels)
    private val channelScroll = browser.findViewById<ScrollView>(R.id.browser_scroll)
    private val browserHeader = browser.findViewById<TextView>(R.id.browser_header)
    private val nextHeader = browser.findViewById<TextView>(R.id.browser_next_header)
    /** What the right-hand list shows: [FAVORITES] or [ALL] channels. */
    private var shownView = NO_BLOCK
    /** The block highlighted in the left column. */
    private var highlight = NO_BLOCK
    /** The channel for each row of the list (null for block headings). */
    private val rowChannels = mutableListOf<LiveChannel?>()
    /** Where each block's heading sits in the list. */
    private val headerIndex = mutableMapOf<Int, Int>()
    private val requestedPrograms = mutableSetOf<String>()
    /** Left from the list lands on the block you're in without moving the list. */
    private var stayInPlace = false

    /** Favorite being moved inside Favorites (Up / Down move it, OK or Back drops it). */
    private var movingId: String? = null

    private val hideRunnable = Runnable { if (mode == Mode.OVERLAY && !paused) closeAll() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateOverlay()
            // Refresh "what's on now" when the current program ends.
            val end = current?.current?.end
            if (end != null && Instant.now().isAfter(end)) loadChannels()
            handler.postDelayed(this, 15_000)
        }
    }

    init {
        host.insertView(overlay)
        host.insertView(browser)

        jumpButton.setOnClickListener { jumpToLive() }
        guideButton.setOnClickListener { openBrowser(guide = true) }
        tracksButton.setOnClickListener { host.openTracksPanel() }

        mpv.setLiveCache()
        mpv.useLiveTvAudio()
        LiveLaunchActivity.remember(ctx, link.streamUrlFor(currentId))
        channelText.text = ""
        loadChannels()
        handler.postDelayed(tickRunnable, 15_000)
        showOverlay()
    }

    // ----- Data -----

    private fun loadChannels() {
        api.async({ channels() }) { list ->
            if (list.isNullOrEmpty()) return@async
            channels = list
            // Remember every channel, so opening one from Jellyfin goes straight to live TV next time.
            prefs.edit().putStringSet("known_channels", list.map { it.id }.toSet()).apply()
            current = list.firstOrNull { it.id == currentId } ?: current
            current?.let { addRecent(it.id) }
            syncDirect()
            updateOverlay()
            if (mode == Mode.OVERLAY && !recentsList.hasFocus()) fillRecents()
            if ((mode == Mode.BLOCKS || mode == Mode.GUIDE) && movingId == null) refreshList(focusedChannelId())
        }
    }

    private fun blockOf(ch: LiveChannel?): Int {
        val n = ch?.numberValue ?: return 0
        return (floor(n).toInt() - 1).coerceAtLeast(0) / 100
    }

    private fun blockLabel(block: Int) =
        if (block == FAVORITES) ctx.getString(R.string.favorites) else "${block * 100 + 1} – ${block * 100 + 100}"

    private fun channelsInBlock(block: Int) =
        if (block == FAVORITES) favoriteIds().mapNotNull { id -> channels.firstOrNull { it.id == id } }
        else channels.filter { blockOf(it) == block }

    // ----- Favorites (saved on this device, in the order set) -----

    private fun favoriteIds(): List<String> =
        prefs.getString("favorites", "").orEmpty().split(',').filter { it.isNotBlank() }

    private fun saveFavorites(ids: List<String>) {
        prefs.edit().putString("favorites", ids.joinToString(",")).apply()
    }

    private fun isFavorite(id: String) = id in favoriteIds()

    // ----- Playing channels -----

    private fun play(ch: LiveChannel, infoOnly: Boolean = false) {
        val currentIdBefore = currentId
        currentId = ch.id
        current = ch
        behindMs = 0
        paused = false
        if (ch.id != currentIdBefore) mpv.resetBoostForNewChannel()
        loadChannel(ch.id)
        mpv.setPaused(false)
        addRecent(ch.id)
        LiveLaunchActivity.remember(ctx, link.streamUrlFor(ch.id))
        closeAll()
        if (infoOnly) showChannelInfo() else showOverlay()
    }

    /**
     * Starts a channel: straight from Dispatcharr when its address is known (faster), with the
     * Jellyfin route as the fallback; otherwise through Jellyfin while the address is looked up.
     */
    private fun loadChannel(id: String) {
        resetStuckWatch()
        val viaJellyfin = link.streamUrlFor(id)
        val direct = DirectStreams.get(ctx, id)
        if (direct != null) mpv.load(direct, 0.0, emptyList(), fallback = viaJellyfin)
        else mpv.load(viaJellyfin, 0.0, emptyList())
    }

    private var directSynced = false

    /**
     * Reads Dispatcharr's playlist once per session and saves each channel's direct address,
     * so channels play straight from Dispatcharr (Jellyfin stays as the fallback).
     */
    private fun syncDirect() {
        if (directSynced || channels.isEmpty()) return
        directSynced = true
        val url = DispatcharrPlaylist.url(ctx) ?: return
        val list = channels
        api.async({ DispatcharrPlaylist.fetch(url) }) { entries ->
            if (entries.isNullOrEmpty()) return@async // unreachable or empty: keep what we had
            val matched = DispatcharrPlaylist.match(list, entries)
            if (matched.isNotEmpty()) DirectStreams.replaceAll(ctx, matched)
        }
    }

    private fun stepChannel(direction: Int) {
        if (channels.isEmpty()) return
        val index = channels.indexOfFirst { it.id == currentId }
        val next = channels[((if (index < 0) 0 else index + direction) + channels.size) % channels.size]
        play(next, infoOnly = true)
    }

    private fun jumpToLive() {
        current?.let { play(it) } ?: run {
            behindMs = 0
            loadChannel(currentId)
            mpv.setPaused(false)
        }
    }

    /** The live stream stopped (network hiccup or the pause buffer ran out): reconnect at the live point. */
    fun onStreamEnded() {
        handler.postDelayed({
            behindMs = 0
            loadChannel(currentId)
            mpv.setPaused(false)
        }, 1000)
    }

    fun onPauseChanged(isPaused: Boolean) {
        if (isPaused == paused) return
        paused = isPaused
        if (isPaused) {
            pausedAt = SystemClock.elapsedRealtime()
            showOverlay()
        } else {
            behindMs += SystemClock.elapsedRealtime() - pausedAt
            scheduleHide()
        }
        updateOverlay()
    }

    // ----- Stuck stream watch -----
    // A problem stream can stall or keep replaying the same few seconds — in any player. Changing
    // the channel and back fixes it, so Pilchard does that by itself (reconnects the same channel),
    // with limits so a truly broken stream isn't reconnected over and over.

    private val bufferStalls = ArrayDeque<Long>()
    private val backJumps = ArrayDeque<Long>()
    private val reconnects = ArrayDeque<Long>()
    private var bufferingSince = 0L
    private var lastPos = -1L
    private var loadedAt = SystemClock.elapsedRealtime()
    private val stallCheck = Runnable {
        if (bufferingSince > 0 && SystemClock.elapsedRealtime() - bufferingSince >= STALL_MS) reconnect()
    }

    private fun resetStuckWatch() {
        bufferStalls.clear()
        backJumps.clear()
        bufferingSince = 0L
        lastPos = -1L
        loadedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(stallCheck)
    }

    /** Start-up buffering is normal; only watch once a channel has been playing a while. */
    private fun settled(now: Long) = now - loadedAt > SETTLE_MS && !paused

    fun onBuffering(buffering: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!buffering) {
            bufferingSince = 0L
            handler.removeCallbacks(stallCheck)
            return
        }
        if (!settled(now)) return
        bufferingSince = now
        handler.removeCallbacks(stallCheck)
        handler.postDelayed(stallCheck, STALL_MS)
        bufferStalls.addLast(now)
        while (bufferStalls.isNotEmpty() && now - bufferStalls.first() > WINDOW_MS) bufferStalls.removeFirst()
        if (bufferStalls.size >= 3) reconnect()
    }

    fun onPosition(sec: Long) {
        val now = SystemClock.elapsedRealtime()
        // Jumping backward (replaying what was just shown) without anyone seeking.
        if (lastPos >= 0 && sec < lastPos - 1 && settled(now)) {
            backJumps.addLast(now)
            while (backJumps.isNotEmpty() && now - backJumps.first() > WINDOW_MS) backJumps.removeFirst()
            if (backJumps.size >= 2) { lastPos = -1L; reconnect(); return }
        }
        lastPos = sec
    }

    private fun reconnect() {
        val now = SystemClock.elapsedRealtime()
        while (reconnects.isNotEmpty() && now - reconnects.first() > GIVE_UP_WINDOW_MS) reconnects.removeFirst()
        if (reconnects.isNotEmpty() && now - reconnects.last() < COOLDOWN_MS) return
        if (reconnects.size >= MAX_RECONNECTS) return
        reconnects.addLast(now)
        android.widget.Toast.makeText(ctx.applicationContext, R.string.reconnecting, android.widget.Toast.LENGTH_SHORT).show()
        behindMs = 0
        loadChannel(currentId)
        mpv.setPaused(false)
    }

    // ----- Recent channels -----

    private fun recentIds(): List<String> =
        prefs.getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() }

    private fun addRecent(id: String) {
        val list = (listOf(id) + recentIds().filter { it != id }).take(10)
        prefs.edit().putString("recent", list.joinToString(",")).apply()
    }

    private fun removeRecent(id: String) {
        prefs.edit().putString("recent", recentIds().filter { it != id }.joinToString(",")).apply()
    }

    /** Recent channels, newest first, to the right of the Guide button. */
    private fun fillRecents() {
        recentsList.removeAllViews()
        val items = recentIds().filter { it != currentId }
            .mapNotNull { id -> channels.firstOrNull { it.id == id } }
            .take(6)
        for (ch in items) {
            val card = inflater.inflate(R.layout.recent_card, recentsList, false)
            card.findViewById<TextView>(R.id.card_number).text = ch.number ?: ""
            card.findViewById<TextView>(R.id.card_name).text = ch.name
            card.findViewById<TextView>(R.id.card_program).text = ch.current?.name ?: ""
            card.findViewById<ProgressBar>(R.id.card_progress).progress = programProgress(ch.current)
            card.setOnClickListener { play(ch) }
            card.setOnLongClickListener { recentMenu(ch, card); true }
            recentsList.addView(card)
        }
    }

    private fun recentMenu(ch: LiveChannel, card: View) {
        val index = recentsList.indexOfChild(card)
        showMenu(ch.label, listOf(ctx.getString(R.string.remove_from_recent))) {
            removeRecent(ch.id)
            fillRecents()
            // Keep focus nearby: the next card, the one before, or the Guide button.
            (recentsList.getChildAt(index) ?: recentsList.getChildAt(index - 1) ?: guideButton).requestFocus()
        }
    }

    /** A small list of choices over the video. [onPick] gets the chosen index; Back cancels. */
    private fun showMenu(title: String, items: List<String>, onPick: (Int) -> Unit) {
        handler.removeCallbacks(hideRunnable)
        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.PilchardMenu)
            .setTitle(title)
            .setItems((items + ctx.getString(R.string.cancel)).toTypedArray()) { _, which ->
                if (which < items.size) onPick(which)
            }
            .setOnDismissListener { if (mode == Mode.OVERLAY) scheduleHide() }
            .show()
    }

    // ----- Channel blocks (left panel) and Guide (bottom half) -----
    // The list on the right holds every channel in one continuous list, with a small heading
    // at each block. The left column jumps: Favorites shows just the favorites; 1–100,
    // 201–300 … scroll the full list to that block.

    private fun openBrowser(guide: Boolean) {
        closeAll()
        mode = if (guide) Mode.GUIDE else Mode.BLOCKS
        val lp = browser.layoutParams as FrameLayout.LayoutParams
        val rootW = root.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        val rootH = root.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels
        val pad = (32 * density).toInt()
        if (guide) {
            // Guide on the bottom half, the channel keeps playing on the top half.
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = rootH / 2
            lp.gravity = Gravity.BOTTOM
            browser.setPadding(pad, (16 * density).toInt(), (24 * density).toInt(), (12 * density).toInt())
            val gap = (12 * density).toInt()
            host.setVideoArea(Rect(0, gap, rootW, rootH / 2 - gap))
        } else {
            // Channel list on the left, the video keeps playing smaller on the right.
            val panelW = (rootW * 0.56f).toInt()
            lp.width = panelW
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.TOP or Gravity.START
            browser.setPadding(pad, pad, (24 * density).toInt(), (24 * density).toInt())
            host.setVideoArea(Rect(panelW + pad, pad * 2, rootW - pad, (rootH * 0.66f).toInt()))
        }
        browser.layoutParams = lp
        nextHeader.visibility = if (guide) View.VISIBLE else View.GONE
        browser.visibility = View.VISIBLE

        if (channels.isEmpty()) {
            blocksList.removeAllViews()
            browserHeader.setText(R.string.loading_channels)
            channelList.removeAllViews()
            rowChannels.clear()
            shownView = NO_BLOCK
            return
        }
        buildBlockRows()
        showBlock(blockOf(current), keepFocus = false, rebuild = true)
    }

    /** The left column: Favorites (when there are any) above 1–100, 101–200 … */
    private fun buildBlockRows() {
        blocksList.removeAllViews()
        for (b in blockOrder()) {
            val row = inflater.inflate(R.layout.block_row, blocksList, false) as TextView
            row.text = if (b == FAVORITES) "★  " + blockLabel(b) else blockLabel(b)
            row.tag = b
            // Moving through the left column previews: Favorites shows them, a number block scrolls to it.
            row.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) return@setOnFocusChangeListener
                if (stayInPlace) { stayInPlace = false; return@setOnFocusChangeListener }
                showBlock(b, keepFocus = true)
            }
            row.setOnClickListener { showBlock(b, keepFocus = false) }
            blocksList.addView(row)
        }
        markHighlight()
    }

    /** The blocks in the left column, in order (Favorites first when there are any). */
    private fun blockOrder(): List<Int> =
        (if (channelsInBlock(FAVORITES).isEmpty()) emptyList() else listOf(FAVORITES)) +
            channels.map { blockOf(it) }.distinct().sorted()

    private fun setHighlight(block: Int) {
        if (block == highlight) return
        highlight = block
        markHighlight()
    }

    private fun markHighlight() {
        for (i in 0 until blocksList.childCount) {
            val row = blocksList.getChildAt(i) as TextView
            row.isSelected = row.tag == highlight
            row.setTypeface(null, if (row.tag == highlight) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    /** Fills the right-hand list: just the favorites, or every channel with a heading per block. */
    private fun buildList(view: Int) {
        shownView = view
        val guide = mode == Mode.GUIDE
        channelList.removeAllViews()
        rowChannels.clear()
        headerIndex.clear()
        val favorites = favoriteIds()
        val list = if (view == FAVORITES) channelsInBlock(FAVORITES) else channels
        var lastBlock = Int.MIN_VALUE
        for (ch in list) {
            if (view == ALL) {
                val b = blockOf(ch)
                if (b != lastBlock) {
                    val header = inflater.inflate(R.layout.panel_header, channelList, false) as TextView
                    header.text = ctx.getString(R.string.channels_range, blockLabel(b))
                    channelList.addView(header)
                    rowChannels.add(null)
                    headerIndex[b] = channelList.childCount - 1
                    lastBlock = b
                }
            }
            channelList.addView(channelRow(ch, guide, favorites, inFavorites = view == FAVORITES))
            rowChannels.add(ch)
        }
        val label = ctx.getString(if (view == FAVORITES) R.string.favorites else R.string.all_channels)
        browserHeader.text = if (guide) ctx.getString(R.string.guide_title, label) else label
        if (guide) loadNextPrograms(list)
    }

    private fun channelRow(ch: LiveChannel, guide: Boolean, favorites: List<String>, inFavorites: Boolean): View {
        val row = inflater.inflate(R.layout.channel_row, channelList, false)
        row.findViewById<TextView>(R.id.ch_number).text = ch.number ?: ""
        row.findViewById<TextView>(R.id.ch_name).text = when {
            ch.id == movingId -> ctx.getString(R.string.moving_channel, ch.name)
            ch.id in favorites -> "★  " + ch.name
            else -> ch.name
        }
        row.findViewById<TextView>(R.id.ch_now).text = ch.current?.let {
            "${it.name} · ${timeFormat.format(it.start ?: Instant.now())}"
        } ?: ctx.getString(R.string.no_info)
        if (guide) {
            val next = row.findViewById<TextView>(R.id.ch_next)
            next.visibility = View.VISIBLE
            next.text = nextText(ch)
        }
        row.findViewById<View>(R.id.ch_playing).visibility = if (ch.id == currentId) View.VISIBLE else View.INVISIBLE
        row.setOnClickListener { if (movingId == null) play(ch) }
        row.setOnLongClickListener { if (movingId == null) channelMenu(ch, if (inFavorites) FAVORITES else blockOf(ch)); true }
        // Scrolling through the full list keeps the left column on the block you're in.
        if (!inFavorites) row.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) setHighlight(blockOf(ch)) }
        return row
    }

    private fun rowOf(id: String?): View? {
        if (id == null) return null
        val i = rowChannels.indexOfFirst { it?.id == id }
        return if (i >= 0) channelList.getChildAt(i) else null
    }

    private fun firstRowOfBlock(block: Int): View? {
        val i = rowChannels.indexOfFirst { it != null && blockOf(it) == block }
        return if (i >= 0) channelList.getChildAt(i) else null
    }

    private fun firstChannelRow(): View? {
        val i = rowChannels.indexOfFirst { it != null }
        return if (i >= 0) channelList.getChildAt(i) else null
    }

    /**
     * Shows [block] in the right-hand list. Favorites shows just the favorites; any number block
     * scrolls the full list to it. [keepFocus]: only scroll (focus stays on the left column).
     * Otherwise focus [focusId], else the channel playing if it's in that block, else the block's
     * first channel. [rebuild]: redraw the list (after channels or favorites change).
     */
    private fun showBlock(block: Int, keepFocus: Boolean, focusId: String? = null, rebuild: Boolean = false) {
        val view = if (block == FAVORITES) FAVORITES else ALL
        if (rebuild || view != shownView) buildList(view)
        setHighlight(block)
        if (keepFocus) {
            channelScroll.post { channelScroll.scrollTo(0, blockTop(block)) }
            return
        }
        val playing = rowOf(currentId)?.takeIf { view == FAVORITES || blockOf(current) == block }
        val target = rowOf(focusId) ?: playing ?: (if (view == ALL) firstRowOfBlock(block) else null) ?: firstChannelRow()
        focusRow(target, alignToBlock = if (target != null && target != playing && focusId == null) block else null)
    }

    /** Where a block starts in the list (its heading), or the top for Favorites. */
    private fun blockTop(block: Int): Int =
        if (shownView == ALL) headerIndex[block]?.let { channelList.getChildAt(it)?.top } ?: 0 else 0

    /** Focuses [row]; [alignToBlock] puts that block's heading at the top, otherwise the row sits a little down. */
    private fun focusRow(row: View?, alignToBlock: Int?) {
        row ?: return
        row.requestFocus()
        channelScroll.post {
            val y = if (alignToBlock != null) blockTop(alignToBlock) else (row.top - row.height * 2).coerceAtLeast(0)
            channelScroll.scrollTo(0, y)
        }
    }

    /**
     * Going right from the left column into the list: the block you're watching opens at the
     * channel playing; any other block opens at its top.
     */
    private fun enterChannelList(): Boolean {
        val playing = rowOf(currentId)?.takeIf { shownView == FAVORITES || blockOf(current) == highlight }
        val target = playing ?: (if (shownView == ALL) firstRowOfBlock(highlight) else null) ?: firstChannelRow() ?: return false
        focusRow(target, alignToBlock = if (target != playing && shownView == ALL) highlight else null)
        return true
    }

    /** Holding Up / Down stops at the ends of a list instead of jumping to the other column. */
    private fun atListEnd(code: Int): Boolean {
        val focused = root.findFocus() ?: return false
        for (list in listOf(channelList, blocksList)) {
            val index = list.indexOfChild(focused)
            if (index < 0) continue
            val focusable = (0 until list.childCount).filter { list.getChildAt(it).isFocusable }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && index == focusable.lastOrNull()) return true
            if (code == KeyEvent.KEYCODE_DPAD_UP && index == focusable.firstOrNull()) return true
        }
        return false
    }

    /** Redraws the list after a change, keeping the same channel selected. */
    private fun refreshList(focusId: String?) {
        buildBlockRows()
        val view = if (shownView == FAVORITES && channelsInBlock(FAVORITES).isNotEmpty()) FAVORITES else ALL
        val block = if (view == FAVORITES) FAVORITES else focusId?.let { id -> channels.firstOrNull { it.id == id } }?.let { blockOf(it) } ?: blockOf(current)
        if (focusId != null) {
            buildList(view)
            setHighlight(block)
            val row = rowOf(focusId) ?: firstChannelRow()
            row?.requestFocus()
            row?.let { r -> channelScroll.post { channelScroll.scrollTo(0, (r.top - r.height * 2).coerceAtLeast(0)) } }
        } else {
            showBlock(block, keepFocus = blocksList.hasFocus(), rebuild = true)
        }
    }

    /** The channel whose row has focus, if any. */
    private fun focusedChannelId(): String? {
        val focused = root.findFocus() ?: return null
        val i = channelList.indexOfChild(focused)
        return if (i >= 0) rowChannels.getOrNull(i)?.id else null
    }

    /** Hold OK on a channel: add / remove favorite; inside Favorites, also Move. */
    private fun channelMenu(ch: LiveChannel, block: Int) {
        val favorite = isFavorite(ch.id)
        val items = mutableListOf<String>()
        if (block == FAVORITES) items += ctx.getString(R.string.move)
        items += ctx.getString(if (favorite) R.string.remove_from_favorites else R.string.add_to_favorites)
        showMenu(ch.label, items) { which ->
            when (items[which]) {
                ctx.getString(R.string.move) -> {
                    movingId = ch.id
                    showBlock(FAVORITES, keepFocus = false, focusId = ch.id, rebuild = true)
                }
                ctx.getString(R.string.add_to_favorites) -> {
                    saveFavorites(favoriteIds() + ch.id)
                    refreshList(ch.id)
                }
                else -> {
                    val ids = favoriteIds()
                    val index = ids.indexOf(ch.id)
                    val remaining = ids - ch.id
                    saveFavorites(remaining)
                    if (block == FAVORITES && remaining.isEmpty()) {
                        // Favorites is gone from the list: go back to this channel in the full list.
                        buildBlockRows()
                        showBlock(blockOf(ch), keepFocus = false, focusId = ch.id, rebuild = true)
                    } else {
                        refreshList(if (block == FAVORITES) remaining.getOrNull(index) ?: remaining.lastOrNull() else ch.id)
                    }
                }
            }
        }
    }

    /** Moves the favorite being moved one place up (-1) or down (+1). */
    private fun moveFavorite(direction: Int) {
        val id = movingId ?: return
        val ids = favoriteIds().toMutableList()
        val from = ids.indexOf(id)
        val to = from + direction
        if (from < 0 || to < 0 || to >= ids.size) return
        ids.removeAt(from)
        ids.add(to, id)
        saveFavorites(ids)
        showBlock(FAVORITES, keepFocus = false, focusId = id, rebuild = true)
    }

    private fun endMove() {
        val id = movingId ?: return
        movingId = null
        showBlock(FAVORITES, keepFocus = false, focusId = id, rebuild = true)
    }

    private fun nextProgram(ch: LiveChannel): Program? {
        val now = Instant.now()
        return nextPrograms[ch.id]?.firstOrNull { it.start != null && it.start.isAfter(now) }
    }

    private fun nextText(ch: LiveChannel): String =
        nextProgram(ch)?.let { "${timeFormat.format(it.start)}  ${it.name}" } ?: ""

    /** "Up next" for the guide, fetched 100 channels at a time, starting around the channel playing. */
    private fun loadNextPrograms(list: List<LiveChannel>) {
        val start = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val ordered = list.drop(start) + list.take(start)
        val ids = ordered.map { it.id }.filter { it !in nextPrograms && it !in requestedPrograms }
        if (ids.isEmpty()) return
        requestedPrograms += ids
        for (chunk in ids.chunked(100)) {
            api.async({ programs(chunk) }) { result ->
                if (result == null) {
                    requestedPrograms -= chunk.toSet()
                    return@async
                }
                nextPrograms = nextPrograms + result
                if (mode == Mode.GUIDE) {
                    for (i in 0 until channelList.childCount) {
                        val ch = rowChannels.getOrNull(i) ?: continue
                        if (ch.id in result) channelList.getChildAt(i).findViewById<TextView>(R.id.ch_next)?.text = nextText(ch)
                    }
                }
            }
        }
    }

    // ----- Overlay -----

    fun showOverlay() {
        if (mode != Mode.NONE && mode != Mode.OVERLAY) return
        val wasShowing = mode == Mode.OVERLAY && overlay.visibility == View.VISIBLE && !surfing
        mode = Mode.OVERLAY
        surfing = false
        overlay.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        tracksButton.visibility = View.VISIBLE
        updateOverlay()
        // The stream details settle a second or two after a channel starts; refresh them once.
        handler.postDelayed({ if (mode == Mode.OVERLAY) streamInfoText.text = mpv.streamInfo() }, 2000)
        if (!wasShowing) fillRecents()
        // Opening the overlay always starts on the Guide button (OK twice opens the guide).
        if (!wasShowing || !overlay.hasFocus()) guideButton.requestFocus()
        scheduleHide()
    }

    /** After Up / Down: just the LIVE badge and channel info (top left) and the clock (top right). */
    private fun showChannelInfo() {
        mode = Mode.OVERLAY
        surfing = true
        bottomBar.visibility = View.GONE
        tracksButton.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        updateOverlay()
        scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 5000)
    }

    private fun updateOverlay() {
        streamInfoText.text = mpv.streamInfo()
        val ch = current
        channelText.text = ch?.label ?: ""
        programText.text = ch?.current?.name ?: ""
        clockText.text = timeFormat.format(Instant.now())
        val program = ch?.current
        startText.text = program?.start?.let { timeFormat.format(it) } ?: ""
        endText.text = program?.end?.let { timeFormat.format(it) } ?: ""
        progress.progress = programProgress(program)

        val behind = behindMs + if (paused) SystemClock.elapsedRealtime() - pausedAt else 0
        when {
            paused -> {
                badge.text = ctx.getString(R.string.paused_behind, duration(behind))
                badge.setBackgroundResource(R.drawable.paused_badge_bg)
            }
            behind > 1500 -> {
                badge.text = ctx.getString(R.string.behind_live, duration(behind))
                badge.setBackgroundResource(R.drawable.paused_badge_bg)
            }
            else -> {
                badge.setText(R.string.live)
                badge.setBackgroundResource(R.drawable.live_badge_bg)
            }
        }
        jumpButton.visibility = if (paused || behind > 1500) View.VISIBLE else View.GONE
        if (paused) handler.postDelayed({ if (paused) updateOverlay() }, 1000)
    }

    private fun programProgress(p: Program?): Int {
        val start = p?.start?.toEpochMilli() ?: return 0
        val end = p.end?.toEpochMilli() ?: return 0
        if (end <= start) return 0
        return (((System.currentTimeMillis() - start).toDouble() / (end - start)) * 1000).toInt().coerceIn(0, 1000)
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ----- Closing panels -----

    private fun closeAll() {
        handler.removeCallbacks(hideRunnable)
        if (mode == Mode.BLOCKS || mode == Mode.GUIDE) host.setVideoArea(null)
        overlay.visibility = View.GONE
        browser.visibility = View.GONE
        mode = Mode.NONE
        surfing = false
        movingId = null
    }

    /** Back button. Returns true if live TV handled it (closed something). */
    fun onBack(): Boolean {
        if (movingId != null) { endMove(); return true }
        if (mode == Mode.NONE) return false
        closeAll()
        return true
    }

    /** Called when the Audio & Subtitles panel closes. */
    fun onPanelClosed() {
        // Closing a channel's schedule goes back to that channel in the guide.
        val back = scheduleReturnId
        scheduleReturnId = null
        if (back != null && (mode == Mode.GUIDE || mode == Mode.BLOCKS)) {
            (rowOf(back) ?: firstChannelRow())?.requestFocus()
            return
        }
        showOverlay()
        tracksButton.requestFocus()
    }

    // ----- A channel's schedule (Right in the guide) -----
    // Loaded only when asked for, one channel at a time, so the guide stays fast.

    private var scheduleReturnId: String? = null

    private fun openSchedule(channelId: String) {
        val ch = channels.firstOrNull { it.id == channelId } ?: return
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        // Until midnight, and always at least the next 3 hours (late at night).
        val threeHours = now.plusHours(3)
        val until = (if (midnight.isAfter(threeHours)) midnight else threeHours).toInstant()
        val hours = ((java.time.Duration.between(now.toInstant(), until).seconds + 3599) / 3600).coerceIn(3, 24)
        scheduleReturnId = channelId
        api.async({ programs(listOf(channelId), hours) }) { result ->
            if (mode != Mode.GUIDE || scheduleReturnId != channelId) return@async
            val nowInstant = Instant.now()
            val rows = result?.get(channelId).orEmpty()
                .filter { (it.end == null || it.end.isAfter(nowInstant)) && (it.start == null || it.start.isBefore(until)) }
                .map { p ->
                    val time = "${p.start?.let { timeFormat.format(it) } ?: "?"} – ${p.end?.let { timeFormat.format(it) } ?: "?"}"
                    val onNow = p.start != null && !p.start.isAfter(nowInstant)
                    p.name.ifBlank { ctx.getString(R.string.no_info) } to
                        (if (onNow) ctx.getString(R.string.on_now, time) else time)
                }
            host.showSchedule(ch.label, rows) { play(ch) }
        }
    }

    // ----- Remote keys -----

    fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val code = event.keyCode
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { stepChannel(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { stepChannel(-1); return true }
            KeyEvent.KEYCODE_GUIDE -> { openBrowser(guide = true); return true }
        }
        // Moving a favorite: Up / Down move it, OK drops it, Left / Right do nothing.
        if (movingId != null && (mode == Mode.BLOCKS || mode == Mode.GUIDE)) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> moveFavorite(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveFavorite(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) endMove()
            }
            return true
        }
        when (mode) {
            Mode.NONE -> return when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { stepChannel(1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { stepChannel(-1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { openBrowser(guide = false); true }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showOverlay(); true }
                else -> false
            }
            Mode.OVERLAY -> {
                // Only the channel info is up (after Up / Down): the remote works as if nothing is showing.
                if (surfing) {
                    return when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> { stepChannel(1); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { stepChannel(-1); true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { openBrowser(guide = false); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showOverlay(); true }
                        else -> false
                    }
                }
                scheduleHide()
                return false
            }
            Mode.BLOCKS, Mode.GUIDE -> {
                if ((code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) && atListEnd(code)) return true
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && blocksList.hasFocus()) return enterChannelList()
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && channelList.hasFocus()) {
                    val row = (0 until blocksList.childCount).map { blocksList.getChildAt(it) }
                        .firstOrNull { it.tag == highlight } ?: blocksList.getChildAt(0) ?: return true
                    stayInPlace = !row.isFocused
                    row.requestFocus()
                    return true
                }
                // Guide: Right on a channel shows its schedule until midnight.
                if (mode == Mode.GUIDE && code == KeyEvent.KEYCODE_DPAD_RIGHT && channelList.hasFocus()) {
                    focusedChannelId()?.let { openSchedule(it) }
                    return true
                }
                // Right from the channel column closes the channel blocks panel.
                if (mode == Mode.BLOCKS && code == KeyEvent.KEYCODE_DPAD_RIGHT && channelList.hasFocus()) {
                    closeAll(); return true
                }
                return false
            }
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val NO_BLOCK = -1
        const val FAVORITES = -2
        const val ALL = -3

        const val SETTLE_MS = 15_000L          // ignore the first 15 s of a channel
        const val STALL_MS = 8_000L            // one stall longer than this → reconnect
        const val WINDOW_MS = 60_000L          // 3 stalls or 2 backward jumps within a minute → reconnect
        const val COOLDOWN_MS = 30_000L        // at least 30 s between reconnects
        const val GIVE_UP_WINDOW_MS = 300_000L // at most 3 reconnects in 5 minutes
        const val MAX_RECONNECTS = 3
    }
}
