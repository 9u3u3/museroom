package com.museroom.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Cover art for somebody else's track.
 *
 * The listener's own phone gets artwork free with the media session, but that is
 * a bitmap in their memory, not something a friend can see. Rather than upload
 * every cover to storage, each viewer looks the track up by name.
 *
 * Apple's search endpoint is used because it needs no key, no account and no
 * quota application, which keeps a cosmetic feature from becoming a dependency
 * anyone has to register for. A miss simply means no picture.
 */
object Artwork {

    private val cache = LruCache<String, Bitmap>(60)
    private val misses = mutableSetOf<String>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun cached(title: String, artist: String): Bitmap? = cache.get(key(title, artist))

    suspend fun fetch(title: String, artist: String): Bitmap? {
        val key = key(title, artist)
        cache.get(key)?.let { return it }
        synchronized(misses) { if (key in misses) return null }

        return withContext(Dispatchers.IO) {
            runCatching {
                val term = URLEncoder.encode(
                    listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "),
                    "UTF-8",
                )
                val request = Request.Builder()
                    .url("https://itunes.apple.com/search?term=$term&entity=song&limit=1")
                    .build()

                val body = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext remember(key, null)
                    response.body?.string().orEmpty()
                }

                val results = JSONObject(body).optJSONArray("results")
                    ?: return@withContext remember(key, null)
                if (results.length() == 0) return@withContext remember(key, null)

                // The endpoint returns a 100px thumbnail; the same path serves larger.
                val url = results.getJSONObject(0).optString("artworkUrl100")
                    .replace("100x100bb", "300x300bb")
                if (url.isBlank()) return@withContext remember(key, null)

                val bytes = http.newCall(Request.Builder().url(url).build()).execute().use {
                    it.body?.bytes()
                } ?: return@withContext remember(key, null)

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                remember(key, bitmap)
            }.getOrElse { remember(key, null) }
        }
    }

    private fun remember(key: String, bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) {
            synchronized(misses) { misses += key }
        } else {
            cache.put(key, bitmap)
        }
        return bitmap
    }

    private fun key(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"
}
