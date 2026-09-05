package com.museroom.app.player

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
    enum class Quality(val ceilingBitrate: Int) {
        /** For a metered connection. The cheapest thing that is still music. */
        Low(72_000),

        /** The default. Indistinguishable from Max on a phone speaker or buds. */
        High(140_000),

        /** Whatever the best offered format is, however large. */
        Max(Int.MAX_VALUE),
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
    ) {
        fun freshAt(nowMs: Long): Boolean = nowMs < expiresAtMs
    }

    /**
     * The best format for a quality, or null if there were none.
     *
     * Highest bitrate that fits under the ceiling, and if nothing fits, the
     * quietest thing on offer rather than nothing: a listener on Low would
     * rather hear the track at whatever bitrate exists than be told no.
     */
    fun pick(formats: List<InnerTube.Format>, quality: Quality): InnerTube.Format? {
        if (formats.isEmpty()) return null
        val within = formats.filter { it.bitrate <= quality.ceilingBitrate }
        return within.maxByOrNull { it.bitrate } ?: formats.minByOrNull { it.bitrate }
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
        val answer = InnerTube.play(videoId, avoid)
        val format = pick(answer.formats, quality)
            ?: throw InnerTube.Unplayable("NO_FORMAT", "nothing playable for $videoId")

        val stream = Stream(
            videoId = videoId,
            url = format.url,
            // The endpoint hands out URLs on the understanding that the client
            // it answered is the one that will fetch them, so the request that
            // follows has to keep saying the same thing.
            headers = mapOf(
                "User-Agent" to (InnerTube.clients.first { it.name == answer.client }.userAgent),
            ),
            itag = format.itag,
            mimeType = format.mimeType,
            bitrate = format.bitrate,
            contentLength = format.contentLength,
            durationMs = if (format.durationMs > 0) format.durationMs else answer.durationMs,
            loudnessDb = format.loudnessDb,
            client = answer.client,
            // Deliberately short of the stated life. A URL that expires while a
            // track is halfway through it stops mid-song, and thirty seconds of
            // unused shelf life is cheaper than that.
            expiresAtMs = nowMs + (answer.expiresInSeconds - 30).coerceAtLeast(30) * 1000L,
        )
        remember(stream, generation)
        return stream
    }
}
