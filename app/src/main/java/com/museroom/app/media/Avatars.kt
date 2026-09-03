package com.museroom.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * People's faces.
 *
 * Small, square and cached by address. The address changes whenever somebody
 * changes their picture, which is what makes caching by address safe: an old
 * one is never asked for again, and a new one is never a stale hit.
 */
object Avatars {

    private val cache = LruCache<String, Bitmap>(80)
    private val misses = mutableSetOf<String>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun cached(url: String?): Bitmap? = url?.let { cache.get(it) }

    /** How wide a face is ever stored. A leaderboard row is 38dp. */
    private const val SIDE = 256

    /**
     * A picture from the gallery, made small enough to be worth uploading.
     *
     * Phones take twelve-megapixel photographs and this is shown at the size of
     * a thumbnail, so it is cropped square and scaled down here rather than
     * sending several megabytes to be thrown away at the other end.
     */
    fun encode(context: android.content.Context, uri: android.net.Uri): ByteArray? = runCatching {
        val source = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it)
        } ?: return null

        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side,
        )
        val small = Bitmap.createScaledBitmap(square, SIDE, SIDE, true)
        java.io.ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
    }.getOrNull()

    suspend fun fetch(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        cache.get(url)?.let { return it }
        synchronized(misses) { if (url in misses) return null }

        return withContext(Dispatchers.IO) {
            val bitmap = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()

            if (bitmap == null) {
                synchronized(misses) { misses += url }
            } else {
                cache.put(url, bitmap)
            }
            bitmap
        }
    }
}
