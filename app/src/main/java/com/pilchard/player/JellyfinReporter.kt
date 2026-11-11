package com.pilchard.player

import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Reports playback progress straight to the Jellyfin server, so the resume point is saved
 * even if the Jellyfin app loses track of the player (a known Jellyfin issue).
 *
 * Everything it needs comes from the stream link Jellyfin hands us:
 *   http://server:8096/Videos/<itemId>/stream?static=true&mediaSourceId=...&api_key=<token>
 * If the link doesn't look like that, reporting is simply skipped.
 */
class JellyfinReporter private constructor(
    val baseUrl: String,
    val token: String,
    val itemId: String,
    private val mediaSourceId: String?,
) {
    private val executor = Executors.newSingleThreadExecutor()

    fun started(positionMs: Long) = post("/Sessions/Playing", body(positionMs, paused = false))

    fun progress(positionMs: Long, paused: Boolean, event: String = "timeupdate") =
        post("/Sessions/Playing/Progress", body(positionMs, paused).put("EventName", event))

    fun stopped(positionMs: Long) {
        val json = JSONObject()
            .put("ItemId", itemId)
            .put("PositionTicks", positionMs * TICKS_PER_MS)
        mediaSourceId?.let { json.put("MediaSourceId", it) }
        post("/Sessions/Playing/Stopped", json)
        executor.shutdown()
    }

    private fun body(positionMs: Long, paused: Boolean): JSONObject {
        val json = JSONObject()
            .put("ItemId", itemId)
            .put("PositionTicks", positionMs * TICKS_PER_MS)
            .put("IsPaused", paused)
            .put("CanSeek", true)
            .put("PlayMethod", "DirectPlay")
        mediaSourceId?.let { json.put("MediaSourceId", it) }
        return json
    }

    private fun post(path: String, json: JSONObject) {
        if (executor.isShutdown) return
        executor.execute {
            try {
                val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", ServerAccount.authHeader(token))
                conn.outputStream.use { it.write(json.toString().toByteArray()) }
                val code = conn.responseCode
                if (code >= 300) Log.w(TAG, "$path returned $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Could not report $path: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "JellyfinReporter"
        private const val TICKS_PER_MS = 10_000L

        fun fromStreamUrl(url: String, fallbackToken: String? = null): JellyfinReporter? {
            val uri = Uri.parse(url)
            if (uri.scheme != "http" && uri.scheme != "https") return null
            val segments = uri.pathSegments
            val videos = segments.indexOfFirst { it.equals("Videos", ignoreCase = true) }
            if (videos < 0 || videos + 1 >= segments.size) return null

            fun param(vararg names: String): String? {
                for (key in uri.queryParameterNames) {
                    if (names.any { it.equals(key, ignoreCase = true) }) return uri.getQueryParameter(key)
                }
                return null
            }

            val token = param("api_key", "ApiKey") ?: fallbackToken ?: return null
            val prefix = segments.subList(0, videos).joinToString("/") { Uri.encode(it) }
            val base = "${uri.scheme}://${uri.encodedAuthority}" + if (prefix.isNotEmpty()) "/$prefix" else ""
            return JellyfinReporter(base, token, segments[videos + 1], param("MediaSourceId"))
        }
    }
}
