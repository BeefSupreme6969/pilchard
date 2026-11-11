package com.pilchard.player

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Keeps the resume point when Pilchard runs under its own app ID (the Play Store build).
 *
 * Jellyfin for Android TV only understands the results of players it knows by app ID.
 * For any other player it reports "stopped, no position" to the server, and the server
 * treats that as "watched to the end": it marks the item played and erases the resume
 * point Pilchard had just saved. That happens a moment after Pilchard closes.
 *
 * So, when playback stops part way through, this watches the item's saved state for a
 * short while. If it gets wiped, it puts back the resume point and the watched state
 * from before playback started. If Jellyfin ever recognizes Pilchard, nothing gets
 * wiped and this does nothing.
 */
class ResumeFixer(
    private val baseUrl: String,
    private val token: String,
    private val userId: String,
    private val itemId: String,
) {
    private data class UserData(val positionTicks: Long, val played: Boolean, val playCount: Int)

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var before: UserData? = null

    /** Remembers the item's watched state before playback, so it can be put back exactly. */
    fun captureBefore() {
        executor.execute { before = read() }
    }

    /**
     * Call when playback stops part way through. Runs in the background for about 30 seconds
     * after Pilchard closes.
     */
    fun guard(positionMs: Long, durationMs: Long) {
        // Near the end, Jellyfin counts it as watched anyway, so there's nothing to keep.
        if (positionMs <= 0 || (durationMs > 0 && positionMs >= durationMs * 9 / 10)) {
            executor.shutdown()
            return
        }
        val ticks = positionMs * TICKS_PER_MS
        executor.execute {
            val start = System.currentTimeMillis()
            Thread.sleep(FIRST_CHECK_MS)
            while (System.currentTimeMillis() - start < WATCH_MS) {
                val now = read()
                if (now != null && looksWiped(now)) {
                    Log.i(TAG, "Resume point was wiped for $itemId; putting it back")
                    restore(ticks)
                    break
                }
                Thread.sleep(CHECK_EVERY_MS)
            }
        }
        executor.shutdown()
    }

    private fun looksWiped(now: UserData): Boolean {
        val was = before
        return if (was != null) {
            now.playCount > was.playCount || (now.played && !was.played)
        } else {
            now.played && now.positionTicks == 0L
        }
    }

    private fun restore(ticks: Long) {
        val was = before
        val body = JSONObject()
            .put("PlaybackPositionTicks", ticks)
            .put("Played", was?.played ?: false)
        was?.let { body.put("PlayCount", it.playCount) }

        // Jellyfin 10.9 and newer: set the item's saved state directly.
        val code = request("POST", "/UserItems/$itemId/UserData?userId=$userId", body)
        if (code in 200..299) return

        // Older servers: mark it unwatched (which also clears the position), then report
        // the stopping point again.
        if (was?.played != true) {
            val unmark = request("DELETE", "/UserPlayedItems/$itemId?userId=$userId", null)
            if (unmark !in 200..299) request("DELETE", "/Users/$userId/PlayedItems/$itemId", null)
        }
        request("POST", "/Sessions/Playing/Stopped", JSONObject().put("ItemId", itemId).put("PositionTicks", ticks))
    }

    private fun read(): UserData? {
        val json = get("/UserItems/$itemId/UserData?userId=$userId")
            ?: get("/Users/$userId/Items/$itemId")?.optJSONObject("UserData")
            ?: return null
        return UserData(
            positionTicks = json.optLong("PlaybackPositionTicks", 0L),
            played = json.optBoolean("Played", false),
            playCount = json.optInt("PlayCount", 0),
        )
    }

    private fun get(path: String): JSONObject? = try {
        val conn = open("GET", path)
        try {
            if (conn.responseCode in 200..299) {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } else null
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read $path: ${e.message}")
        null
    }

    private fun request(method: String, path: String, json: JSONObject?): Int = try {
        val conn = open(method, path)
        try {
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toString().toByteArray()) }
            }
            conn.responseCode.also { if (it >= 300) Log.w(TAG, "$method $path returned $it") }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not $method $path: ${e.message}")
        -1
    }

    private fun open(method: String, path: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Authorization", ServerAccount.authHeader(token))
        }

    companion object {
        private const val TAG = "ResumeFixer"
        private const val TICKS_PER_MS = 10_000L
        private const val FIRST_CHECK_MS = 2_000L
        private const val CHECK_EVERY_MS = 2_000L
        private const val WATCH_MS = 30_000L
    }
}
