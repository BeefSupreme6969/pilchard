package com.pilchard.player

import android.content.Context
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Dispatcharr's channel playlist (its M3U output), used to play live channels straight from
 * Dispatcharr instead of through Jellyfin. Channels are matched to Jellyfin's by number, then name.
 */
object DispatcharrPlaylist {

    private const val PREFS = "dispatcharr"
    private const val KEY = "playlist_url"

    data class Entry(val name: String, val tvgName: String?, val number: Double?, val url: String)

    /** The saved address; otherwise a guess: the Jellyfin server's machine on Dispatcharr's port. */
    fun url(context: Context): String? {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let {
            return it.ifBlank { null } // saved as blank = turned off
        }
        val base = ServerAccount.get(context)?.baseUrl ?: ServerAccount.lastSeenServer(context) ?: return null
        val host = Uri.parse(base).host ?: return null
        return "http://$host:9191/output/m3u"
    }

    fun save(context: Context, url: String) {
        val clean = url.trim().let { if (it.isEmpty() || it.startsWith("http")) it else "http://$it" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, clean).apply()
    }

    /** Downloads and reads the playlist (call off the main thread). */
    fun fetch(url: String): List<Entry> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            if (conn.responseCode >= 300) error("Playlist returned ${conn.responseCode}")
            return parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var info: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> info = line
                line.isEmpty() || line.startsWith("#") -> Unit
                info != null -> {
                    val attrs = attr.findAll(info).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val name = info.substringAfterLast(',').trim()
                    val number = (attrs["tvg-chno"] ?: attrs["channel-number"] ?: attrs["tvg-channel-number"])
                        ?.toDoubleOrNull()
                    out += Entry(name, attrs["tvg-name"], number, line)
                    info = null
                }
            }
        }
        return out
    }

    /** Jellyfin channel id → Dispatcharr stream address, for the channels that can be matched. */
    fun match(channels: List<LiveChannel>, entries: List<Entry>): Map<String, String> {
        val byNumber = entries.filter { it.number != null }.groupBy { it.number }
        fun norm(s: String?) = s?.trim()?.lowercase()?.ifEmpty { null }
        val byName = entries.flatMap { e -> listOfNotNull(norm(e.name), norm(e.tvgName)).map { it to e } }
            .groupBy({ it.first }, { it.second })
        val result = mutableMapOf<String, String>()
        for (ch in channels) {
            val n = ch.numberValue
            val numbered = n?.let { v -> byNumber.entries.firstOrNull { abs(it.key!! - v) < 0.001 }?.value }
            val entry = numbered?.singleOrNull()
                ?: norm(ch.name)?.let { byName[it] }?.singleOrNull()
                ?: numbered?.firstOrNull { norm(it.name) == norm(ch.name) }
            if (entry != null) result[ch.id] = entry.url
        }
        return result
    }
}
