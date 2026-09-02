package com.museroom.app.media

import android.graphics.Bitmap

/**
 * One music app's playback, snapshotted. Positions are carried as the player
 * reported them plus the clock reading at that moment, never as "the position
 * right now", so a snapshot stays correct however long it sits in a queue.
 */
data class NowPlaying(
    val packageName: String,
    val sourceLabel: String,
    /** Whether this player is one Museroom counts. */
    val isTracked: Boolean,
    /**
     * The player's own id for this track, when it publishes one. With it a
     * friend can be sent to the exact song instead of to a search.
     */
    val sourceTrackId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** Position the player last reported. */
    val reportedPositionMs: Long,
    /** SystemClock.elapsedRealtime() at which the player reported it. */
    val reportedAtElapsed: Long,
    val playbackSpeed: Float,
    val isPlaying: Boolean,
    /**
     * What the player says it is emitting: music, speech, a movie soundtrack.
     * A video app and a music app both publish a media session, and only this
     * tells them apart.
     */
    val audioContentType: Int,
    val artwork: Bitmap?,
    val rawMetadata: Map<String, String>,
) {

    val fingerprint: String get() = Fingerprint.of(title, artist, durationMs)

    /** Plain-language form of [audioContentType], for the diagnostics panel. */
    val contentKind: String
        get() = when (audioContentType) {
            android.media.AudioAttributes.CONTENT_TYPE_MUSIC -> "music"
            android.media.AudioAttributes.CONTENT_TYPE_SPEECH -> "speech"
            android.media.AudioAttributes.CONTENT_TYPE_MOVIE -> "video"
            android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION -> "sound effect"
            else -> "unspecified"
        }

    /**
     * The live timestamp. We never ask the player where it is; it told us once,
     * along with when and how fast, and the rest is arithmetic. A friend's phone
     * runs this same function against the last snapshot it received.
     */
    fun positionAt(elapsedRealtime: Long): Long {
        val base = if (isPlaying) {
            val drift = elapsedRealtime - reportedAtElapsed
            reportedPositionMs + (drift * playbackSpeed).toLong()
        } else {
            reportedPositionMs
        }
        val ceiling = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return base.coerceIn(0L, ceiling)
    }
}
