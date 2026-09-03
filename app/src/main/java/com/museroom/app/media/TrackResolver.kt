package com.museroom.app.media

import android.content.Context
import com.museroom.app.BuildConfig
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.Supabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Turning a song title into a link that opens that exact song.
 *
 * This is the whole difference between following somebody automatically and
 * dumping them in a search box. A player will start a track you hand it by id;
 * it will not guess which search result you meant.
 *
 * Resolution is shared. The first phone to hear a song looks it up and writes
 * the answer to the catalogue; every other phone reads it. That matters because
 * the lookup spends a small daily quota, and per-track-once scales where
 * per-play-per-person does not.
 */
object TrackResolver {

    private val memory = mutableMapOf<String, String?>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val configured: Boolean get() = BuildConfig.YOUTUBE_API_KEY.isNotBlank()

    /**
     * A search that needs no key, installed by whoever has one.
     *
     * Museroom's own player runs a signed-in YouTube Music page, and that page
     * can search as the person using it. Letting it answer here is what makes
     * following work out of the box: the key becomes an optimisation rather
     * than a requirement, and the answer still lands in the shared catalogue.
     */
    @Volatile
    var searcher: (suspend (title: String, artist: String) -> String?)? = null

    /** A YouTube video id for this track, or null if it cannot be found. */
    suspend fun youtubeId(
        context: Context,
        title: String,
        artist: String,
        durationMs: Long,
    ): String? {
        if (title.isBlank()) return null
        val fingerprint = Fingerprint.of(title, artist, durationMs)
        synchronized(memory) { if (memory.containsKey(fingerprint)) return memory[fingerprint] }

        return withContext(Dispatchers.IO) {
            val shared = fromCatalogue(context, fingerprint)
            if (shared != null) return@withContext remember(fingerprint, shared)

            val found = search(title, artist) ?: return@withContext remember(fingerprint, null)
            publish(context, fingerprint, title, artist, durationMs, found)
            remember(fingerprint, found)
        }
    }

    /** What somebody else already resolved. */
    private fun fromCatalogue(context: Context, fingerprint: String): String? = runCatching {
        val token = tokenOf(context) ?: return null
        val body = Supabase.select(
            "track_aliases",
            "fingerprint=eq.${URLEncoder.encode(fingerprint, "UTF-8")}" +
                "&select=tracks(youtube_video_id)&limit=1",
            token,
        )
        Supabase.json.parseToJsonElement(body).jsonArray.firstOrNull()
            ?.jsonObject?.get("tracks")?.jsonObject
            ?.get("youtube_video_id")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private suspend fun search(title: String, artist: String): String? =
        byKey(title, artist) ?: searcher?.invoke(title, artist)

    private fun byKey(title: String, artist: String): String? {
        if (!configured) return null
        val query = URLEncoder.encode(
            listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "),
            "UTF-8",
        )
        val url = "https://www.googleapis.com/youtube/v3/search" +
            "?part=snippet&type=video&videoCategoryId=10&maxResults=1&q=$query" +
            "&key=${BuildConfig.YOUTUBE_API_KEY}"

        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val items = JSONObject(response.body?.string().orEmpty()).optJSONArray("items")
                if (items == null || items.length() == 0) return null
                items.getJSONObject(0).optJSONObject("id")?.optString("videoId")
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** Writes the answer back so nobody else spends a lookup on this track. */
    private fun publish(
        context: Context,
        fingerprint: String,
        title: String,
        artist: String,
        durationMs: Long,
        videoId: String,
    ) {
        val token = tokenOf(context) ?: return
        runCatching {
            val track = buildJsonArray {
                add(
                    buildJsonObject {
                        put("title", title)
                        put("artist", artist)
                        put("duration_ms", durationMs)
                        put("youtube_video_id", videoId)
                    },
                )
            }
            Supabase.insert("tracks", track, token)

            val body = Supabase.select(
                "tracks",
                "youtube_video_id=eq.$videoId&select=id&limit=1",
                token,
            )
            val id = Supabase.json.parseToJsonElement(body).jsonArray.firstOrNull()
                ?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return

            val alias = buildJsonArray {
                add(
                    buildJsonObject {
                        put("fingerprint", fingerprint)
                        put("track_id", id)
                        put("source", "youtube")
                    },
                )
            }
            Supabase.insert("track_aliases", alias, token, upsertOnConflict = "fingerprint")
        }
    }

    private fun tokenOf(context: Context): String? =
        AuthRepository.get(context).session.value?.accessToken

    private fun remember(fingerprint: String, value: String?): String? {
        synchronized(memory) { memory[fingerprint] = value }
        return value
    }
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
