package com.museroom.app.media

/**
 * Collapses the strings a player hands us into something stable enough that two
 * plays of the same song agree, across both apps.
 *
 * This is the single most important piece of text processing in the product. If
 * one song fingerprints three ways, minutes and top-artist counts fragment and
 * the leaderboard quietly stops being trustworthy.
 */
object Fingerprint {

    /** " - Topic", "VEVO" and friends that YouTube Music glues onto artist names. */
    private val artistSuffixes = listOf(
        " - topic", " vevo", " - official", " official",
    )

    /** "(Official Video)", "[Lyrics]", "- Remastered 2011" and similar. */
    private val noiseParens = Regex("""[\(\[\{][^)\]\}]*(official|video|audio|lyric|lyrics|visualizer|hd|4k|mv|m/v|explicit|remaster(ed)?( \d{4})?|live|version)[^)\]\}]*[\)\]\}]""")
    private val trailingDash = Regex("""\s+-\s+(official\s+)?(music\s+)?(video|audio|lyrics?|visualizer|remaster(ed)?( \d{4})?)\s*$""")
    private val featuring = Regex("""\s*[\(\[]?\s*(feat\.?|ft\.?|featuring)\s+[^)\]]*[\)\]]?\s*$""")
    private val whitespace = Regex("""\s+""")
    private val punctuation = Regex("""[^\p{L}\p{Nd}\s]""")

    /**
     * Apostrophes are dropped rather than turned into a space, so that "Don't"
     * and "Dont" land on one track. Every other mark becomes a separator.
     */
    private val apostrophes = Regex("""['\u2018\u2019\u02BC\u00B4`]""")

    /**
     * Whitespace is removed outright rather than collapsed.
     *
     * Real listening turned up "F o r C e r t a i n" from one app for a track
     * another app calls "For Certain". Collapsing runs of spaces keeps those two
     * apart; deleting spaces entirely brings them together, and the artist and
     * duration bucket still carry enough signal to keep genuinely different songs
     * separate. The readable strings are untouched, this only shapes the key.
     */
    private fun flatten(s: String): String = s
        .replace(apostrophes, "")
        .replace(punctuation, " ")
        .replace(whitespace, "")
        .trim()

    fun title(raw: String): String = raw
        .lowercase()
        .replace(noiseParens, " ")
        .replace(trailingDash, " ")
        .replace(featuring, " ")
        .let(::flatten)

    fun artist(raw: String): String {
        var s = raw.lowercase().trim()
        // YouTube Music often sends "Artist - Topic", and sometimes "Artist · Album".
        s = s.substringBefore(" · ").trim()
        for (suffix in artistSuffixes) {
            if (s.endsWith(suffix)) s = s.dropLast(suffix.length).trim()
        }
        // Multiple artists arrive in half a dozen shapes. Keep the primary one.
        s = s.split(",", " & ", " x ", " feat. ", " ft. ").first().trim()
        return flatten(s)
    }

    /**
     * Duration is bucketed because the two apps disagree by a second or so on the
     * same track, usually over trailing silence.
     */
    fun of(rawTitle: String, rawArtist: String, durationMs: Long): String {
        val bucket = if (durationMs > 0) durationMs / 2000 else -1
        return "${title(rawTitle)}|${artist(rawArtist)}|$bucket"
    }
}
