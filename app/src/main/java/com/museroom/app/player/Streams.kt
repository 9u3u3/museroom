package com.museroom.app.player

import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Turning a video id into something ExoPlayer can read, and remembering it.
 *
 * Two things happen here and they are deliberately not the same thing. Picking
 * a format is a decision about sound and data, made from what the endpoint
 * offered. Holding on to the answer is a decision about cost: resolving is a
 * network round trip, the URL that comes back is good for hours, and a room
 * that re-resolved on every seek would spend those hours asking again.
 */
object Streams {

    /** What the listener asked for, not what the network happened to allow. */
    enum class Quality {
        /** For a metered connection. The cheapest thing that is still music. */
        Low,

        /** The default. Indistinguishable from Max on a phone speaker or buds. */
        High,

        /** Whatever the best offered format is, however large. */
        Max,
    }

    /** A URL and everything needed to use it, including when it stops working. */
    data class Stream(
        val videoId: String,
        val url: String,
        val headers: Map<String, String>,
        val itag: Int,
        val mimeType: String,
        val bitrate: Int,
        val contentLength: Long,
        val durationMs: Long,
        val loudnessDb: Double?,
        val client: String,
        val expiresAtMs: Long,
        /**
         * How much may be asked for at once.
         *
         * Not a tuning knob. Some of these addresses refuse a request that is
         * not a range, and some refuse a range that is too large, and which is
         * which depends on the client that issued it. The extractor knows, so
         * it says, and the player asks for exactly that.
         */
        val boundedRange: Boolean = false,
        val chunkBytes: Long = 0,
    ) {
        fun freshAt(nowMs: Long): Boolean = nowMs < expiresAtMs
    }

    // ------------------------------------------------------------- the cache --

    private class Entry(val stream: Stream, val generation: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private val generations = HashMap<String, Long>()

    /** Enough to cover an album and a queue without holding a session's worth. */
    private const val KEEP = 200

    /**
     * A stream we already have, if it has not expired.
     *
     * Expiry is checked on the way out rather than swept on a timer, because a
     * track nobody asks for again does not need us to have noticed.
     */
    @Synchronized
    fun cached(videoId: String, nowMs: Long = System.currentTimeMillis()): Stream? {
        val entry = entries[videoId] ?: return null
        if (!entry.stream.freshAt(nowMs)) {
            forget(videoId)
            return null
        }
        return entry.stream
    }

    /** What generation a video id is on, so a slow answer can tell it is stale. */
    @Synchronized
    fun generation(videoId: String): Long = generations[videoId] ?: 0L

    /**
     * Stores an answer, unless the id was invalidated while it was in flight.
     *
     * Without the generation check, this sequence loses: a resolve starts, the
     * stream is rejected and invalidated, a second resolve finishes and stores
     * a good URL, then the first finally returns and overwrites it with the URL
     * that was already known to be bad.
     */
    @Synchronized
    fun remember(stream: Stream, expectedGeneration: Long = generation(stream.videoId)): Boolean {
        if (generation(stream.videoId) != expectedGeneration) return false
        entries[stream.videoId] = Entry(stream, expectedGeneration)
        while (entries.size > KEEP) {
            entries.remove(entries.keys.first())
        }
        return true
    }

    /** Drops a stream and moves the id on, so answers already in flight lose. */
    @Synchronized
    fun forget(videoId: String) {
        entries.remove(videoId)
        generations[videoId] = (generations[videoId] ?: 0L) + 1L
    }

    @Synchronized
    fun forgetEverything() {
        entries.clear()
        generations.clear()
    }

    // ---------------------------------------------------------------- resolve --

    /**
     * The cached stream, or a new one.
     *
     * Blocking on purpose. The only caller is ExoPlayer's resolving data
     * source, which runs on its own loading thread and has nothing useful to do
     * until this answers.
     */
    fun resolve(
        videoId: String,
        quality: Quality = Quality.High,
        avoid: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
    ): Stream {
        cached(videoId, nowMs)?.let { if (avoid.isEmpty()) return it }

        val generation = generation(videoId)
        val found = runBlocking { Extraction.extract(videoId, quality, avoid) }

        val stream = Stream(
            videoId = videoId,
            url = found.audioUrl,
            // The address was issued to whichever client asked for it, on the
            // understanding that the same client would come and fetch it.
            headers = found.headers,
            itag = found.itag,
            mimeType = listOfNotNull(
                found.mimeType,
                found.codecs?.takeIf { it.isNotBlank() }?.let { "codecs=\"$it\"" },
            ).joinToString("; "),
            bitrate = found.bitrate ?: 0,
            contentLength = found.contentLengthBytes ?: -1,
            durationMs = (found.mediaMetadata?.durationSeconds ?: 0L) * 1000,
            loudnessDb = found.loudnessDb,
            client = found.clientName,
            // Deliberately short of the stated life. An address that expires
            // while a track is halfway through it stops mid-song, and thirty
            // seconds of unused shelf life is cheaper than that.
            expiresAtMs = found.expiresAt
                ?.let { it.toEpochMilliseconds() - 30_000 }
                ?.coerceAtLeast(nowMs + 30_000)
                ?: (nowMs + 5 * 60_000),
            boundedRange = found.requireBoundedRange || found.useRangeChunks,
            chunkBytes = found.rangeChunkSizeBytes,
        )
        Log.i(
            "MuseroomPlayer",
            "resolved $videoId via ${stream.client}, itag ${stream.itag} at ${stream.bitrate}bps" +
                if (stream.boundedRange) ", ${stream.chunkBytes} byte chunks" else "",
        )
        remember(stream, generation)
        return stream
    }
}
