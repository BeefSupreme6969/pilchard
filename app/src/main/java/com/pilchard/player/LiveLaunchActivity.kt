package com.pilchard.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * The "Pilchard Live TV" tile on the home screen: opens straight to the last live TV channel
 * watched, with Pilchard's own guide, favorites and recent channels — no Jellyfin guide in between.
 *
 * It replays the stream link Jellyfin last handed over for live TV (pointed at the last channel),
 * so it works once a channel has been opened from Jellyfin at least once.
 */
class LiveLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerAccount.init(this)
        val url = lastLiveStream(this)
        when {
            ServerAccount.get(this) == null -> {
                Toast.makeText(applicationContext, R.string.live_launch_sign_in, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java))
            }
            url == null -> Toast.makeText(applicationContext, R.string.live_launch_first_time, Toast.LENGTH_LONG).show()
            else -> startActivity(Intent(this, PlayerActivity::class.java).setData(Uri.parse(url)))
        }
        finish()
    }

    companion object {
        private const val PREFS = "live"
        private const val KEY = "last_stream"

        /** Called by live TV whenever a channel starts, so the home screen tile can reopen it. */
        fun remember(context: Context, streamUrl: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, streamUrl).apply()
        }

        fun lastLiveStream(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }
}
