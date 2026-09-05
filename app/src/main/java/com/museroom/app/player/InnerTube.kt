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

    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

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
                last = e
            }
        }
        throw last ?: Unplayable("NO_CLIENT", "every client was excluded")
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
