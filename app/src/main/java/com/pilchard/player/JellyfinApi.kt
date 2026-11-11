package com.pilchard.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.Executors

/**
 * The stream link Jellyfin hands us, e.g.
 *   http://server:8096/Videos/<itemId>/stream?static=true&mediaSourceId=<id>&api_key=<token>
 * It carries everything needed to talk to the server: address, access token and item.
 */
class JellyfinLink private constructor(
    val baseUrl: String,
    /** From the link itself, or Pilchard's own Jellyfin sign-in (Jellyfin's TV app sends none). */
    val token: String?,
    val itemId: String,
    private val original: Uri,
    private val itemSegment: Int,
) {
    /** The same kind of link, pointing at another item (used to switch live TV channels). */
    fun streamUrlFor(id: String): String {
        val segments = original.pathSegments.toMutableList()
        segments[itemSegment] = id
        val builder = original.buildUpon()
            .encodedPath("/" + segments.joinToString("/") { Uri.encode(it) })
            .clearQuery()
        for (key in original.queryParameterNames) {
            val value = if (key.equals("MediaSourceId", ignoreCase = true)) id else original.getQueryParameter(key)
            builder.appendQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    companion object {
        fun parse(url: String, fallbackToken: String? = null): JellyfinLink? {
            val uri = Uri.parse(url)
            if (uri.scheme != "http" && uri.scheme != "https") return null
            val segments = uri.pathSegments
            val videos = segments.indexOfFirst { it.equals("Videos", ignoreCase = true) }
            if (videos < 0 || videos + 1 >= segments.size) return null
            val token = uri.queryParameterNames
                .firstOrNull { it.equals("api_key", true) || it.equals("ApiKey", true) }
                ?.let { uri.getQueryParameter(it) } ?: fallbackToken
            val prefix = segments.subList(0, videos).joinToString("/") { Uri.encode(it) }
            val base = "${uri.scheme}://${uri.encodedAuthority}" + if (prefix.isNotEmpty()) "/$prefix" else ""
            return JellyfinLink(base, token, segments[videos + 1], uri, videos + 1)
        }
    }
}

data class Program(val name: String, val start: Instant?, val end: Instant?)

data class LiveChannel(
    val id: String,
    val name: String,
    val number: String?,
    val current: Program?,
) {
    /** Numeric channel number for sorting and blocks ("4.1" → 4.1). */
    val numberValue: Double? = number?.toDoubleOrNull()
    val label: String get() = if (number.isNullOrBlank()) name else "$number · $name"
}

/** Small Jellyfin client for live TV, using the access token from the stream link. */
class JellyfinApi(private val link: JellyfinLink) {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var userId: String? = null

    /** Runs [work] in the background and hands the result (or null on failure) to [done] on the main thread. */
    fun <T> async(work: JellyfinApi.() -> T, done: (T?) -> Unit) {
        executor.execute {
            val result = try { work() } catch (e: Exception) {
                Log.w(TAG, "Jellyfin request failed: ${e.message}")
                null
            }
            main.post { done(result) }
        }
    }

    fun userId(): String = userId ?: request("GET", "/Users/Me").getString("Id").also { userId = it }

    fun itemType(id: String): String? =
        request("GET", "/Users/${userId()}/Items/$id").optString("Type").ifBlank { null }

    fun itemName(id: String): String? =
        request("GET", "/Users/${userId()}/Items/$id").optString("Name").ifBlank { null }

    /** Every live TV channel with what's on now, sorted by channel number. */
    fun channels(): List<LiveChannel> {
        val json = request(
            "GET",
            "/LiveTv/Channels?userId=${userId()}&addCurrentProgram=true&enableImages=false&enableUserData=false"
        )
        val items = json.optJSONArray("Items") ?: JSONArray()
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            LiveChannel(
                id = o.getString("Id"),
                name = o.optString("Name"),
                number = o.optString("ChannelNumber").ifBlank { null },
                current = o.optJSONObject("CurrentProgram")?.let(::program),
            )
        }.sortedWith(compareBy<LiveChannel> { it.numberValue ?: Double.MAX_VALUE }.thenBy { it.name.lowercase() })
    }

    /** Programs on [channelIds] from now until [hours] ahead, grouped by channel and sorted by start. */
    fun programs(channelIds: List<String>, hours: Long = 4): Map<String, List<Program>> {
        if (channelIds.isEmpty()) return emptyMap()
        val now = Instant.now()
        val body = JSONObject()
            .put("UserId", userId())
            .put("ChannelIds", JSONArray(channelIds))
            .put("MinEndDate", now.toString())
            .put("MaxStartDate", now.plusSeconds(hours * 3600).toString())
            .put("SortBy", JSONArray(listOf("StartDate")))
            .put("EnableImages", false)
            .put("EnableUserData", false)
        val items = request("POST", "/LiveTv/Programs", body).optJSONArray("Items") ?: JSONArray()
        val result = mutableMapOf<String, MutableList<Program>>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val channel = o.optString("ChannelId")
            if (channel.isBlank()) continue
            result.getOrPut(channel) { mutableListOf() } += program(o)
        }
        return result.mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    private fun program(o: JSONObject) = Program(
        name = o.optString("Name").ifBlank { o.optString("EpisodeTitle") },
        start = parseDate(o.optString("StartDate")),
        end = parseDate(o.optString("EndDate")),
    )

    private fun parseDate(s: String?): Instant? = try {
        if (s.isNullOrBlank()) null else Instant.parse(if (s.endsWith("Z") || s.contains('+')) s else s + "Z")
    } catch (_: Exception) { null }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = URL(link.baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", ServerAccount.authHeader(link.token))
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            if (code >= 300) error("$method $path returned $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "JellyfinApi"

        @Suppress("unused")
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
