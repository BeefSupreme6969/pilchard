package com.pilchard.player

import android.content.Context

/**
 * Each live channel's own stream address (Dispatcharr's), from Dispatcharr's playlist.
 * Playing it directly skips Jellyfin in the middle (Shield → Dispatcharr instead of
 * Shield → Jellyfin → Dispatcharr), which starts channels faster — the way TiviMate connects.
 * Saved on the device so channels watched before start directly next time too.
 */
object DirectStreams {
    private fun prefs(context: Context) = context.getSharedPreferences("direct_streams", Context.MODE_PRIVATE)

    fun get(context: Context, channelId: String): String? = prefs(context).getString(channelId, null)

    fun put(context: Context, channelId: String, url: String) {
        prefs(context).edit().putString(channelId, url).apply()
    }

    /** Replaces every saved address (after reading Dispatcharr's playlist). */
    fun replaceAll(context: Context, urls: Map<String, String>) {
        val e = prefs(context).edit().clear()
        for ((id, url) in urls) e.putString(id, url)
        e.apply()
    }

    fun forget(context: Context, channelId: String) {
        prefs(context).edit().remove(channelId).apply()
    }
}
