package com.pilchard.player

import android.content.Context
import java.util.Locale
import kotlin.math.pow

/** Sound settings, remembered between movies. */
class AudioSettings(context: Context) {
    private val prefs = context.getSharedPreferences("audio", Context.MODE_PRIVATE)

    /**
     * Live TV keeps its own sound settings, which turn off on every channel change (streams vary
     * too much). The movie settings stay saved and are back the next time a movie starts.
     */
    var liveTv = false

    private fun key(name: String) = if (liveTv) "${name}_live" else name
    private val boostKey get() = key("boost_db")

    /** Extra loudness in dB, 0 to [MAX_BOOST_DB]. Movies and live TV each have their own. */
    var boostDb: Int
        get() = prefs.getInt(boostKey, 0)
        set(v) = prefs.edit().putInt(boostKey, v.coerceIn(0, MAX_BOOST_DB)).apply()

    /** Night mode: brings loud and quiet parts closer together. */
    var nightMode: Boolean
        get() = prefs.getBoolean(key("night"), false)
        set(v) = prefs.edit().putBoolean(key("night"), v).apply()

    /** Voice boost: makes dialogue clearer. */
    var voiceBoost: Boolean
        get() = prefs.getBoolean(key("voice"), false)
        set(v) = prefs.edit().putBoolean(key("voice"), v).apply()

    /**
     * Surround passthrough: Dolby Digital (AC3) and Dolby Digital Plus (E-AC3) go to the TV or
     * soundbar untouched, as long as no sound effect is on. Same setting for movies and live TV.
     * Off unless turned on in Audio & Subtitles (some TVs lose sound when it switches).
     */
    var passthrough: Boolean
        get() = prefs.getBoolean("passthrough", false)
        set(v) = prefs.edit().putBoolean("passthrough", v).apply()

    /** Volume boost, night mode or voice boost need Pilchard to decode the sound itself. */
    val anyEffect: Boolean get() = boostDb > 0 || nightMode || voiceBoost

    companion object {
        const val MAX_BOOST_DB = 20
        const val BOOST_STEP_DB = 2
    }
}

/**
 * Builds mpv's audio filter chain (ffmpeg filters) from [AudioSettings], plus the volume boost.
 *
 * Volume boost works like Kodi's amplification: plain gain in the player's own audio engine
 * (mpv's volume above 100%), not a filter. A limiter in front of the gain is set lower by the
 * same amount, so the loudest moments end up just under full scale after the boost instead
 * of crackling, while everything quieter (dialogue) really does get louder.
 *
 * Voice boost: on 5.1 / 7.1 audio, dialogue lives almost entirely in the center channel,
 * so the center is raised 6 dB. On stereo, the frequencies voices sit in (about 1–4 kHz)
 * are raised and the deep bass is lowered a little.
 * Night mode: a compressor that tames loud peaks and lifts quiet parts.
 * A limiter always runs last so nothing crackles when boosted.
 */
object AudioEffects {

    private val layouts = mapOf(
        "3.0" to listOf("FL", "FR", "FC"),
        "4.0" to listOf("FL", "FR", "FC", "BC"),
        "5.0" to listOf("FL", "FR", "FC", "BL", "BR"),
        "5.0(side)" to listOf("FL", "FR", "FC", "SL", "SR"),
        "5.1" to listOf("FL", "FR", "FC", "LFE", "BL", "BR"),
        "5.1(side)" to listOf("FL", "FR", "FC", "LFE", "SL", "SR"),
        "6.1" to listOf("FL", "FR", "FC", "LFE", "BC", "SL", "SR"),
        "7.1" to listOf("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR"),
    )

    fun filterChain(settings: AudioSettings, channelLayout: String?): String {
        val parts = mutableListOf<String>()
        if (settings.voiceBoost) parts += voiceFilter(channelLayout)
        if (settings.nightMode) {
            // Fast compressor: catches explosions within a few milliseconds and lifts quiet speech.
            // Typical result: dialogue and action scenes end up only a few dB apart.
            parts += "acompressor=threshold=0.04:ratio=6:attack=3:release=300:knee=4:makeup=4"
        }
        if (parts.isEmpty() && settings.boostDb == 0) return ""
        // Leaves room for the boost that mpv adds afterwards (see volumePercent).
        val limit = (0.97 * 10.0.pow(-settings.boostDb / 20.0)).coerceAtLeast(0.0625)
        parts += "alimiter=limit=${"%.4f".format(Locale.US, limit)}:attack=3:release=80:level=0"
        return "lavfi=[" + parts.joinToString(",") + "]"
    }

    /**
     * mpv's volume for a boost in dB. mpv's volume is cubic (gain = (volume/100)^3),
     * so +6 dB is about 126 and +20 dB about 215.
     */
    fun volumePercent(boostDb: Int): String =
        "%.1f".format(Locale.US, 100.0 * 10.0.pow(boostDb / 60.0))

    private fun voiceFilter(channelLayout: String?): String {
        val layout = channelLayout?.trim()?.lowercase()
        val ids = layouts[layout]
        if (layout != null && ids != null) {
            val mapping = ids.joinToString("|") { if (it == "FC") "FC=2*FC" else "$it=$it" }
            return "pan=$layout|$mapping"
        }
        // Stereo / mono: lift the speech range, trim the rumble.
        return "equalizer=f=2500:t=q:w=1.2:g=5," +
            "equalizer=f=1200:t=q:w=1:g=2," +
            "equalizer=f=120:t=q:w=1:g=-3"
    }
}
