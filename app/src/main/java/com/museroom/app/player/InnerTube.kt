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
 * YouTube's player endpoint, asked as a media client instead of as a page.
 *
 * This is the whole reason Museroom can stop renting a hidden WebView. The
 * YouTube Music web application is a page with a queue, an ad break and a mind
 * of its own; `youtubei/v1/player` is a request that answers with a list of
 * audio formats and a URL for each. We only ever wanted the second thing.
 *
 * Which client we claim to be decides what comes back, and the difference is
 * not cosmetic. Ask as the web player and the URLs arrive with their signatures
 * scrambled by JavaScript that changes weekly, and increasingly with nothing at
 * all until you have run Google's bot check in a browser. Ask as a device that
 * has no browser to run it in and the same recording comes back as a plain URL.
 * That is not a loophole we found; it is what every media client on a television
 * or a headset has to be given.
 *
 * Clients break one at a time, so there is a list rather than a favourite, and
 * a caller can name the ones that have just failed it.
 */
object InnerTube {

    /**
     * One identity we can present.
     *
     * The user agent is not decoration. The endpoint cross-checks it against
     * the client name in the body, and a mismatch is answered with an error
     * rather than a stream.
     */
    data class Client(
        val name: String,
        val version: String,
        val userAgent: String,
        val extra: JsonObject = JsonObject(emptyMap()),
    )

    private val ANDROID_VR = Client(
        name = "ANDROID_VR",
        version = "1.65.10",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12; GB) gzip",
        extra = buildJsonObject {
            put("deviceMake", "Oculus")
            put("deviceModel", "Quest 3")
            put("androidSdkVersion", 32)
            put("osName", "Android")
            put("osVersion", "12")
        },
    )

    private val IOS = Client(
        name = "IOS",
        version = "20.10.4",
        userAgent = "com.google.ios.youtube/20.10.4 " +
            "(iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        extra = buildJsonObject {
            put("deviceMake", "Apple")
            put("deviceModel", "iPhone16,2")
            put("osName", "iPhone")
            put("osVersion", "18.3.2.22D82")
        },
    )

    /** Tried in order. The headset first because it answers with the most formats. */
    val clients = listOf(ANDROID_VR, IOS)

    /** One audio format, as the endpoint describes it. */
    data class Format(
        val itag: Int,
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val contentLength: Long,
        val durationMs: Long,
        val sampleRate: Int,
        val loudnessDb: Double?,
    ) {
        /** Opus and AAC need different extractors, and the caller has to say which. */
        val isWebm: Boolean get() = mimeType.startsWith("audio/webm")
    }

    /** Everything one answer gave us, including how long the URLs stay good. */
    data class Answer(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val formats: List<Format>,
        val expiresInSeconds: Int,
        val client: String,
    )

    /**
     * A refusal we can explain, kept apart from a network failure because they
     * call for different things: one is worth trying another client for, the
     * other is worth trying again later.
     */
    class Unplayable(val status: String, val reason: String?) :
        RuntimeException("YouTube will not play this ($status)${reason?.let { ": $it" } ?: ""}")

    /**
     * The web client, which is no good for playback and is exactly right for
     * everything else. Search, browse and lyrics are not gated behind the bot
     * check; only streaming data is. So we ask as a headset for audio and as a
     * browser for words, which is the honest description of what each one is
     * for.
     */
    private val WEB_REMIX = Client(
        name = "WEB_REMIX",
        version = "1.20250310.01.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    )

    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val SEARCH = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"

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

    /**
     * Asks each client in turn until one answers with audio.
     *
     * [avoid] is the set of client names that have just failed for this exact
     * recording, which the player fills in when a stream it was given turned
     * out not to play. Without it a track that one client is wrong about would
     * fail the same way every time it was asked for.
     */
    fun play(videoId: String, avoid: Set<String> = emptySet()): Answer {
        var last: Exception? = null
        for (client in clients) {
            if (client.name in avoid) continue
            try {
                val answer = ask(videoId, client)
                if (answer.formats.isNotEmpty()) return answer
                last = Unplayable("NO_AUDIO", "no audio formats from ${client.name}")
            } catch (e: Exception) {
                android.util.Log.w("MuseroomStream", "${client.name} refused $videoId: ${e.message}")
                last = e
            }
        }
        throw last ?: Unplayable("NO_CLIENT", "every client was excluded")
    }

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
                    put("clientName", WEB_REMIX.name)
                    put("clientVersion", WEB_REMIX.version)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("query", query)
            put("params", SONGS_ONLY)
        }
        val request = Request.Builder()
            .url(SEARCH)
            .header("User-Agent", WEB_REMIX.userAgent)
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
    private fun biggestThumbnail(item: JsonObject): String {
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
    private fun runsIn(element: kotlinx.serialization.json.JsonElement): String {
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

    /** One request, one client, parsed into what the player actually needs. */
    fun ask(videoId: String, client: Client): Answer {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    for ((key, value) in client.extra) put(key, value)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("videoId", videoId)
            // Both of these are the caller saying it has already decided the
            // viewer may see this. Without them an age-gated or flagged track
            // comes back refused rather than playable.
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", client.userAgent)
            .header("X-Goog-Api-Format-Version", "2")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val text = http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Unplayable("HTTP_${response.code}", payload.take(200))
            }
            payload
        }
        return parse(videoId, text, client.name)
    }

    /**
     * Kept separate from the request so it can be tested against a saved
     * answer. The shape of this response is the one thing here that changes
     * without warning, and a fixture is the only way to notice.
     */
    fun parse(videoId: String, payload: String, client: String): Answer {
        val root = json.parseToJsonElement(payload).jsonObject

        val playability = root["playabilityStatus"]?.jsonObject
        val status = playability?.get("status")?.jsonPrimitive?.content ?: "UNKNOWN"
        if (status != "OK") {
            throw Unplayable(status, playability?.get("reason")?.jsonPrimitive?.content)
        }

        val streaming = root["streamingData"]?.jsonObject
        val details = root["videoDetails"]?.jsonObject

        val formats = streaming?.get("adaptiveFormats")?.jsonArray.orEmpty()
            .mapNotNull { element ->
                val f = element.jsonObject
                val mime = f["mimeType"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (!mime.startsWith("audio")) return@mapNotNull null
                // A format whose URL is still scrambled is one we cannot use.
                // Saying so here rather than later means the client rollover
                // gets a chance instead of the player failing on a bad URL.
                val url = f["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                Format(
                    itag = f["itag"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null,
                    url = url,
                    mimeType = mime,
                    bitrate = f["bitrate"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    contentLength = f["contentLength"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1,
                    durationMs = f["approxDurationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    sampleRate = f["audioSampleRate"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    loudnessDb = f["loudnessDb"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                )
            }

        return Answer(
            videoId = videoId,
            title = details?.get("title")?.jsonPrimitive?.content.orEmpty(),
            artist = details?.get("author")?.jsonPrimitive?.content.orEmpty(),
            durationMs = (details?.get("lengthSeconds")?.jsonPrimitive?.content?.toLongOrNull() ?: 0) * 1000,
            formats = formats,
            // Answers carry their own shelf life. Six hours is the usual, but
            // it is stated rather than assumed, and a URL used after it expires
            // is refused rather than slow.
            expiresInSeconds = streaming?.get("expiresInSeconds")
                ?.jsonPrimitive?.content?.toIntOrNull() ?: 300,
            client = client,
        )
    }
}
