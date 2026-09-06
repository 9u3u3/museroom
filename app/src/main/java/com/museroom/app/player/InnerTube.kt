package com.museroom.app.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * YouTube's search, asked as a browser.
 *
 * Playback and search sit behind different doors, and it is worth saying which
 * is which. Streaming data is what the bot check guards, and getting at it needs
 * the token machinery in `Extraction`. Search does not. So Museroom asks as a
 * browser for words and leaves the audio to the extractor, which is an honest
 * description of what each request is for and keeps the cheap half cheap.
 */
object InnerTube {

    /**
     * Who we say we are when we search.
     *
     * The web client is useless for audio and exactly right for everything
     * else, which is the whole point of the split above.
     */
    private const val CLIENT = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20250310.01.00"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private const val SEARCH = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val NEXT = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"

    /**
     * YouTube Music's "songs" filter, as the site itself sends it.
     *
     * Without it a search returns music videos, which are a different recording
     * of the same song with a different length. In a room that difference is
     * everybody hearing a slightly different track and the follow loop trying to
     * correct for something that is not drift.
     */
    private const val SONGS_ONLY = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** A song somebody could choose. */
    data class Found(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        /**
         * The square cover, which the search response carries and the video
         * still does not. YouTube's own thumbnail for a song is a 4:3 frame
         * with the sleeve boxed inside it, so using it means either black bars
         * or cropping the art.
         */
        val artworkUrl: String = "",
    )

    /** Songs matching a query, best first, or empty if the search found none. */
    fun search(query: String, limit: Int = 20): List<Found> {
        if (query.isBlank()) return emptyList()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("query", query)
            put("params", SONGS_ONLY)
        }
        val request = Request.Builder()
            .url(SEARCH)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        return results(text, limit)
    }

    /**
     * Fifty songs that go with this one.
     *
     * YouTube will build a radio around any track, and it will do it without an
     * account, which is the whole reason this is here. Museroom knows what you
     * actually played because it has been counting minutes since long before it
     * could play anything, so it can seed a set of suggestions from that rather
     * than showing an empty shelf to anybody who has not signed in to YouTube.
     */
    fun radio(seedId: String, limit: Int = 25): List<Found> {
        if (seedId.isBlank()) return emptyList()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("videoId", seedId)
            // The prefix is what turns "play this" into "play things like this".
            put("playlistId", "RDAMVM$seedId")
            put("isAudioOnly", true)
        }
        val request = Request.Builder()
            .url(NEXT)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        // The seed itself comes back first. It is the song they just heard, so
        // showing it as a suggestion would be a strange thing to do.
        return queued(text, limit + 1).filterNot { it.id == seedId }.take(limit)
    }

    /** The songs in a queue response, in the order the radio put them. */
    fun queued(payload: String, limit: Int = 25): List<Found> {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()
        val found = mutableListOf<Found>()
        walkQueue(root) { item ->
            if (found.size >= limit) return@walkQueue
            val id = runCatching { item["videoId"]!!.jsonPrimitive.content }.getOrNull()
                ?: return@walkQueue
            val title = item["title"]?.let(::runsIn).orEmpty()
            if (title.isBlank()) return@walkQueue
            val byline = item["longBylineText"]?.let(::runsIn).orEmpty()
                .split("•").map { it.trim() }.filter { it.isNotEmpty() }
            found += Found(
                id = id,
                title = title,
                artist = byline.firstOrNull().orEmpty(),
                // The second line is the album on a song and a view count on a
                // video, and a number of views is not an album.
                album = byline.getOrNull(1)?.takeUnless { it.contains(" views") }.orEmpty(),
                durationMs = item["lengthText"]?.let(::runsIn)?.let(::clock) ?: 0,
                artworkUrl = biggestThumbnail(item),
            )
        }
        return found
    }

    private fun walkQueue(
        element: kotlinx.serialization.json.JsonElement,
        onItem: (JsonObject) -> Unit,
    ) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                if (key == "playlistPanelVideoRenderer" && value is JsonObject) onItem(value)
                else walkQueue(value, onItem)
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { walkQueue(it, onItem) }
            else -> Unit
        }
    }

    /**
     * Reads the search response by hunting for the one renderer that matters
     * rather than by describing the whole tree.
     *
     * The tree is deep, it is versioned by nobody, and the shelves around a
     * result move about between releases. What has not moved is that a song is a
     * `musicResponsiveListItemRenderer` with a video id somewhere inside it and
     * its text in flex columns. Walking for that survives a redesign of
     * everything around it; a full description of the layout does not.
     */
    fun results(payload: String, limit: Int = 20): List<Found> {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()
        val found = mutableListOf<Found>()
        walk(root) { item ->
            if (found.size >= limit) return@walk
            val id = firstString(item, "videoId") ?: return@walk
            val columns = item["flexColumns"]?.jsonArray ?: return@walk
            val lines = columns.map { runsIn(it) }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return@walk
            val title = lines.first()
            val parts = lines.getOrNull(1)?.split("•")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
            val duration = parts.lastOrNull()?.let { clock(it) } ?: 0
            found += Found(
                id = id,
                title = title,
                artist = parts.firstOrNull().orEmpty(),
                album = if (parts.size > 2) parts[parts.size - 2] else "",
                durationMs = duration,
                artworkUrl = biggestThumbnail(item),
            )
        }
        return found
    }

    /**
     * The largest thumbnail anywhere inside one result.
     *
     * Largest rather than first: the same renderer offers the sleeve at several
     * sizes, and the small one is visibly soft behind a full-width player.
     */
    fun biggestThumbnail(item: JsonObject): String {
        var best = ""
        var area = 0
        fun visit(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is JsonObject -> {
                    val url = runCatching { e["url"]!!.jsonPrimitive.content }.getOrNull()
                    val width = runCatching { e["width"]!!.jsonPrimitive.content.toInt() }.getOrNull()
                    val height = runCatching { e["height"]!!.jsonPrimitive.content.toInt() }.getOrNull()
                    if (url != null && width != null && height != null && width * height > area) {
                        area = width * height
                        best = url
                    }
                    e.values.forEach { visit(it) }
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { visit(it) }
                else -> Unit
            }
        }
        visit(item)
        return enlarge(best)
    }

    /**
     * Asks Google's image host for the sleeve at a useful size.
     *
     * These URLs end in the dimensions they were rendered for, and a list ships
     * the sixty-pixel one. Blown up to the width of a phone that is visibly
     * soft, and the fix is a different number rather than a different request:
     * the host resizes on demand.
     */
    private fun enlarge(url: String): String =
        if (url.isBlank()) url
        else Regex("=w\\d+-h\\d+").replace(url, "=w544-h544")

    /** Every musicResponsiveListItemRenderer in the tree, in the order it appears. */
    private fun walk(element: kotlinx.serialization.json.JsonElement, onItem: (JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                if (key == "musicResponsiveListItemRenderer" && value is JsonObject) onItem(value)
                else walk(value, onItem)
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { walk(it, onItem) }
            else -> Unit
        }
    }

    private fun firstString(element: kotlinx.serialization.json.JsonElement, key: String): String? {
        when (element) {
            is JsonObject -> {
                for ((k, v) in element) {
                    if (k == key) {
                        val text = runCatching { v.jsonPrimitive.content }.getOrNull()
                        if (!text.isNullOrBlank()) return text
                    }
                    firstString(v, key)?.let { return it }
                }
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { child ->
                firstString(child, key)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    /** Everything a renderer's runs say, joined, wherever they are nested. */
    fun runsIn(element: kotlinx.serialization.json.JsonElement): String {
        val out = StringBuilder()
        fun visit(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k == "runs" && v is kotlinx.serialization.json.JsonArray) {
                        v.forEach { run ->
                            runCatching { run.jsonObject["text"]!!.jsonPrimitive.content }
                                .getOrNull()?.let { out.append(it) }
                        }
                    } else visit(v)
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { visit(it) }
                else -> Unit
            }
        }
        visit(element)
        return out.toString().trim()
    }

    /** "4:09" or "1:02:11" as milliseconds, or zero when it is not a time at all. */
    fun clock(text: String): Long {
        val parts = text.split(":")
        if (parts.size !in 2..3) return 0
        val numbers = parts.map { it.trim().toIntOrNull() ?: return 0 }
        val seconds = numbers.fold(0) { total, n -> total * 60 + n }
        return seconds * 1000L
    }
}
