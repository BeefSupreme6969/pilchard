package com.pilchard.player

import java.util.Locale

/**
 * Picks the audio and subtitles a video starts with.
 *
 * The family version always picks English audio. Subtitles start off, except English "forced"
 * subtitles (they only translate foreign-language parts, like an alien or a sign), which stay on.
 *
 * The Play Store version does the same in the TV's language instead of English. If a video has
 * no audio in that language, it also turns on full subtitles in that language when there are some.
 *
 * Full subtitles can always be turned on in Audio & Subtitles; [pickSubtitle] ranks them.
 */
object LanguagePicker {

    /** One language and the ways video files label it: codes, English name, its own name. */
    class Language(val code: String, private val codes: Set<String>, private val names: List<String>) {

        fun matchesCode(raw: String?): Boolean {
            val c = raw?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-') ?: return false
            if (c.isEmpty()) return false
            return c in codes || c.substringBefore('-') in codes || c in names
        }

        /** True when a label like "Spanish (SDH)" or "Español - SRT" names this language. */
        fun matchesName(text: String?): Boolean {
            val t = text?.lowercase(Locale.ROOT) ?: return false
            if (names.any { it.length >= 4 && it in t }) return true
            return codes.any { Regex("(^|[^a-z])${Regex.escape(it)}([^a-z]|$)").containsMatchIn(t) }
        }

        companion object {
            // Video files often use the older "bibliographic" 3-letter codes; Android uses the newer ones.
            private val bibliographic = mapOf(
                "fra" to "fre", "deu" to "ger", "zho" to "chi", "nld" to "dut", "ces" to "cze",
                "ell" to "gre", "fas" to "per", "ron" to "rum", "slk" to "slo", "cym" to "wel",
                "hye" to "arm", "eus" to "baq", "kat" to "geo", "isl" to "ice", "mkd" to "mac",
                "msa" to "may", "sqi" to "alb", "mya" to "bur", "bod" to "tib",
            )
            // Old and new Android codes for the same language.
            private val aliases = mapOf(
                "he" to setOf("iw", "heb"), "iw" to setOf("he", "heb"),
                "id" to setOf("in", "ind"), "in" to setOf("id", "ind"),
                "yi" to setOf("ji", "yid"), "ji" to setOf("yi", "yid"),
                "nb" to setOf("no", "nob", "nor"), "no" to setOf("nb", "nob", "nor"),
                "fil" to setOf("tl", "tgl"), "tl" to setOf("fil"),
            )

            fun of(locale: Locale): Language {
                val two = locale.language.lowercase(Locale.ROOT).ifEmpty { "en" }
                val three = try { locale.isO3Language.lowercase(Locale.ROOT) } catch (e: Exception) { "" }
                val codes = buildSet {
                    add(two)
                    if (three.isNotEmpty()) add(three)
                    bibliographic[three]?.let { add(it) }
                    aliases[two]?.let { addAll(it) }
                }
                val names = listOf(
                    locale.getDisplayLanguage(Locale.ENGLISH),
                    locale.getDisplayLanguage(locale),
                ).map { it.lowercase(Locale.ROOT) }.filter { it.isNotBlank() && it != two }.distinct()
                return Language(two, codes, names)
            }

            val ENGLISH: Language = of(Locale.ENGLISH)
        }
    }

    /** English in the family version; the TV's language in the Play Store version. */
    val preferred: Language by lazy {
        if (BuildConfig.FOLLOW_DEVICE_LANGUAGE) Language.of(Locale.getDefault()) else Language.ENGLISH
    }

    fun isPreferred(t: Track): Boolean {
        if (preferred.matchesCode(t.lang)) return true
        return (t.lang.isNullOrBlank() || t.lang.equals("und", true)) && preferred.matchesName(t.title)
    }

    /** Language code for a subtitle file Jellyfin passed along, guessed from its name ("" if unknown). */
    fun guessCode(name: String?): String = when {
        preferred.matchesName(name) -> preferred.code
        Language.ENGLISH.matchesName(name) -> "en"
        else -> ""
    }

    private fun isPictureSub(t: Track): Boolean {
        val codec = t.codec?.lowercase(Locale.ROOT) ?: return false
        return listOf("pgs", "dvd_sub", "dvdsub", "dvb_sub", "dvbsub", "xsub", "vobsub").any { it in codec }
    }

    private fun isCommentary(t: Track) = t.title?.contains("comment", ignoreCase = true) == true

    private fun isSdh(t: Track): Boolean {
        if (t.isHearingImpaired) return true
        val title = t.title?.lowercase(Locale.ROOT) ?: return false
        return "sdh" in title || "hearing" in title || Regex("\\bcc\\b").containsMatchIn(title)
    }

    fun apply(tracks: List<Track>, mpv: MpvController) {
        val audio = pickAudio(tracks)
        audio?.let { mpv.selectAudio(it.id) }
        // Play Store version: no audio in the TV's language, so show its subtitles instead.
        val sub = if (audio == null && BuildConfig.FOLLOW_DEVICE_LANGUAGE) pickSubtitle(tracks) else null
        mpv.selectSub((sub ?: pickForcedSubtitle(tracks))?.id)
    }

    /** Forced subtitles (foreign-language parts only) in the preferred language, or null for subtitles off. */
    fun pickForcedSubtitle(tracks: List<Track>): Track? =
        tracks.filter { it.type == "sub" && it.isForced && isPreferred(it) && !isCommentary(it) }
            .sortedWith(compareBy<Track> { isPictureSub(it) }.thenByDescending { it.isDefault }.thenBy { it.id })
            .firstOrNull()

    fun pickAudio(tracks: List<Track>): Track? =
        tracks.filter { it.type == "audio" && isPreferred(it) && !isCommentary(it) }
            .sortedWith(
                compareByDescending<Track> { it.isDefault }
                    .thenByDescending { it.channels ?: 0 }
                    .thenBy { it.id }
            )
            .firstOrNull()

    fun pickSubtitle(tracks: List<Track>): Track? =
        tracks.filter { it.type == "sub" && isPreferred(it) && !isCommentary(it) }
            .sortedWith(
                compareBy<Track> { it.isForced }          // full subtitles before forced-only
                    // Text subtitles (SRT, ASS…) before picture ones (PGS, DVD): Pilchard draws text
                    // subtitles itself, so 4K HDR keeps playing in full quality. Picture subtitles
                    // need GPU mode, which shows 4K HDR at 1080p without HDR.
                    .thenBy { isPictureSub(it) }
                    .thenBy { isSdh(it) }                 // regular before SDH / CC
                    .thenByDescending { it.isDefault }
                    .thenBy { it.id }
            )
            .firstOrNull()

    /**
     * "English", "Spanish", ... from a language code like "eng" or "es", or null if unknown.
     * The family version always names languages in English; the Play Store version names them
     * in the TV's language ("Inglés", "Español" on a Spanish TV).
     */
    fun languageName(code: String?): String? {
        val raw = code?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-')
        if (raw.isNullOrEmpty() || raw == "und") return null
        val base = raw.substringBefore('-')
        // The older "bibliographic" codes Android doesn't know.
        val known = mapOf("fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "cze" to "cs",
            "gre" to "el", "per" to "fa", "rum" to "ro", "slo" to "sk", "wel" to "cy", "arm" to "hy",
            "baq" to "eu", "geo" to "ka", "ice" to "is", "mac" to "mk", "may" to "ms", "alb" to "sq",
            "bur" to "my", "tib" to "bo")
        val lang = known[base] ?: base
        val shownIn = if (BuildConfig.FOLLOW_DEVICE_LANGUAGE) Locale.getDefault() else Locale.ENGLISH
        val name = Locale(lang).getDisplayLanguage(shownIn)
        if (name.isNullOrBlank() || name.equals(lang, ignoreCase = true)) return code
        return name.replaceFirstChar { it.titlecase(shownIn) }
    }
}
