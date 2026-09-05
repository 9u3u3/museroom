package com.museroom.app.media

import android.graphics.Bitmap

/**
 * The package a room reports itself under.
 *
 * Deliberately not in the allowlist. This is the one session Museroom builds
 * rather than reads, so it must never be picked up as though somebody's own
 * copy of Museroom were a music player somebody else could be recorded
 * through. It is named here rather than inside a room because two different
 * places now have to recognise it: the room that writes it, and the rule that
 * decides which of several sessions the person actually means.
 */
const val ROOM_PACKAGE = "com.museroom.app"

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
     * An advert, rather than anything anybody chose to play.
     *
     * Carried instead of thrown away, because to a room the difference between
     * an advert and a phone put down is the difference between waiting and
     * giving up. Nothing about the advert itself travels with it: the title,
     * the artist and the length are all cleared where it is detected.
     */
    val isAdvert: Boolean = false,
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

    /** Museroom's own player, rather than an app it is reading. */
    val isRoom: Boolean get() = packageName == ROOM_PACKAGE

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
