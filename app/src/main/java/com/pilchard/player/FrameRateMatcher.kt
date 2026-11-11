package com.pilchard.player

import android.app.Activity
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches the TV's refresh rate to match the movie (for example 24 Hz for film, 50 Hz for 25 fps),
 * so motion is smooth instead of juddery. Works on the Shield when
 * Settings → Display & Sound → Advanced settings → "Match content frame rate" is on.
 * The TV goes back to its normal rate when the player closes.
 */
object FrameRateMatcher {

    private const val TAG = "FrameRateMatcher"

    fun match(activity: Activity, fps: Double?) {
        if (fps == null || fps < 10 || fps > 121) return
        val display = if (Build.VERSION.SDK_INT >= 30) activity.display else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        } ?: return

        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }

        // Prefer the exact rate (24 for 24), then whole multiples (48, 72, 120 for 24; 50 for 25; 59.94 for 29.97).
        val best = (1..5).firstNotNullOfOrNull { multiple ->
            val target = fps * multiple
            sameSize
                .filter { abs(it.refreshRate - target) < 0.08 }
                .minByOrNull { abs(it.refreshRate - target) }
        } ?: return

        if (best.modeId == current.modeId) return
        Log.i(TAG, "Content ${"%.3f".format(fps)} fps → switching display to ${best.refreshRate} Hz")
        val params = activity.window.attributes
        params.preferredDisplayModeId = best.modeId
        activity.window.attributes = params
    }

    /** Hands the screen mode back to the system (the TV's normal rate) before the player closes. */
    fun reset(activity: Activity) {
        val params = activity.window.attributes
        if (params.preferredDisplayModeId == 0) return
        params.preferredDisplayModeId = 0
        activity.window.attributes = params
    }

    /** Rounded rate for display, like "23.976" or "25". */
    fun label(fps: Double): String =
        if (abs(fps - fps.roundToInt()) < 0.01) fps.roundToInt().toString() else "%.3f".format(fps)
}
