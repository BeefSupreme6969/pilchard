package com.pilchard.livetv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** The "Pilchard Live TV" home screen tile: opens live TV in Pilchard Player, then gets out of the way. */
class OpenLiveTvActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(
                Intent()
                    .setClassName(PLAYER_PACKAGE, "com.pilchard.player.LiveLaunchActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(applicationContext, R.string.player_missing, Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(applicationContext, R.string.player_missing, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private companion object {
        const val PLAYER_PACKAGE = "is.xyz.mpv"
    }
}
