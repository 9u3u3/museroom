package com.museroom.app.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The words, and when they are sung.
 *
 * LRCLIB is used because it needs no key, no account and no quota application,
 * the same reason the artwork lookup uses the one it does. A cosmetic feature
 * should not become something anybody has to register for.
 *
 * Timed lines are the point. A page of text somebody has to find their place in
 * is a worse version of a lyrics site; a line that arrives as it is sung is the
 * thing worth building.
 */
object Lyrics {

    /** One line and the moment it starts. */
    data class Line(val atMs: Long, val text: String)

    data class Words(
        val lines: List<Line>,
        /** False when all we found was a block of text with no timings. */
        val timed: Boolean,
        val source: String = "LRCLIB",
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Answers, and misses, so a track without words is asked about once. */
    private val memory = HashMap<String, Words?>()

    @Synchronized
    private fun remembered(key: String) = memory[key]

    @Synchronized
    private fun remember(key: String, words: Words?) {
        if (memory.size > 100) memory.clear()
        memory[key] = words
    }

    /**
     * The words for a track, or null when there are none to be had.
     *
     * Asked for exactly first, since the exact endpoint matches on length as
     * well as name and so cannot hand back a different recording of the same
     * song. Only if that misses do we search, where the top hit is a guess.
     */
    fun of(track: LocalPlayer.Track): Words? {
        if (track.title.isBlank()) return null
        val key = track.id
        if (memory.containsKey(key)) return remembered(key)

        val words = runCatching { exact(track) ?: searched(track) }.getOrNull()
        remember(key, words)
        return words
    }

    private fun exact(track: LocalPlayer.Track): Words? {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", track.artist)
            .addQueryParameter("track_name", track.title)
            .apply { if (track.album.isNotBlank()) addQueryParameter("album_name", track.album) }
            .apply {
                if (track.durationMs > 0) {
                    addQueryParameter("duration", (track.durationMs / 1000).toString())
                }
            }
            .build()
        return fetch(url.toString())?.let(::readOne)
    }

    private fun searched(track: LocalPlayer.Track): Words? {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", track.artist)
            .addQueryParameter("track_name", track.title)
            .build()
        val body = fetch(url.toString()) ?: return null
        val first = runCatching {
            json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject
        }.getOrNull() ?: return null
        return readObject(first)
    }

    private fun fetch(url: String): String? {
        val request = Request.Builder()
            .url(url)
            // LRCLIB asks callers to say who they are, which is a fair ask for
            // something given away with no key.
            .header("User-Agent", "Museroom (https://9u3u3.github.io/museroom/)")
            .build()
        return http.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }

    private fun readOne(payload: String): Words? =
        runCatching { readObject(json.parseToJsonElement(payload).jsonObject) }.getOrNull()

    private fun readObject(node: kotlinx.serialization.json.JsonObject?): Words? {
        node ?: return null
        val instrumental = runCatching {
            node["instrumental"]!!.jsonPrimitive.content.toBoolean()
        }.getOrDefault(false)
        if (instrumental) return null

        val synced = runCatching { node["syncedLyrics"]!!.jsonPrimitive.content }.getOrNull()
        if (!synced.isNullOrBlank()) {
            val lines = parse(synced)
            if (lines.isNotEmpty()) return Words(lines, timed = true)
        }
        val plain = runCatching { node["plainLyrics"]!!.jsonPrimitive.content }.getOrNull()
        if (plain.isNullOrBlank()) return null
        val lines = plain.lines().map { Line(0, it.trim()) }.filter { it.text.isNotBlank() }
        return if (lines.isEmpty()) null else Words(lines, timed = false)
    }

    /**
     * Reads the LRC format: a bracketed time, then the line.
     *
     * A line can carry several stamps when a phrase repeats, and a stamp can
     * carry no words at all, which is how a file marks an instrumental break.
     * Both are kept: the empty ones are what make the highlight sit still
     * during a solo instead of running ahead.
     */
    fun parse(lrc: String): List<Line> {
        val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        val out = mutableListOf<Line>()
        for (raw in lrc.lines()) {
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            for (match in stamps) {
                val minutes = match.groupValues[1].toLongOrNull() ?: continue
                val seconds = match.groupValues[2].toLongOrNull() ?: continue
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                out += Line(minutes * 60_000 + seconds * 1_000 + millis, text)
            }
        }
        return out.sortedBy { it.atMs }
    }

    /** Which line is being sung at this moment, or -1 before the first one. */
    fun lineAt(lines: List<Line>, positionMs: Long): Int {
        var found = -1
        for ((i, line) in lines.withIndex()) {
            if (line.atMs <= positionMs) found = i else break
        }
        return found
    }
}
