package com.museroom.app.media

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * Turns a MediaController into a [NowPlaying]. Everything here is defensive:
 * players are free to omit any field, and YouTube Music omits several.
 */
internal fun MediaController.toNowPlaying(): NowPlaying? {
    val metadata = metadata ?: return null
    val state = playbackState

    val title = metadata.text(MediaMetadata.METADATA_KEY_TITLE)
        ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        ?: return null

    val artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
        ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        ?: ""

    val album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
    val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)

    val playing = state?.state == PlaybackState.STATE_PLAYING

    // A player that has never published a position update leaves lastPositionUpdateTime
    // at 0, which would make the extrapolation run away. Anchor to now instead.
    val reportedAt = state?.lastPositionUpdateTime?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
    val speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f

    return NowPlaying(
        packageName = packageName,
        sourceLabel = Sources.label(packageName),
        isTracked = Sources.isSupported(packageName),
        title = title,
        artist = artist,
        album = album,
        durationMs = duration,
        reportedPositionMs = state?.position?.coerceAtLeast(0L) ?: 0L,
        reportedAtElapsed = reportedAt,
        playbackSpeed = if (playing) speed else 0f,
        isPlaying = playing,
        audioContentType = runCatching { playbackInfo?.audioAttributes?.contentType }
            .getOrNull() ?: AudioAttributes.CONTENT_TYPE_UNKNOWN,
        artwork = metadata.artwork(),
        rawMetadata = metadata.dump(),
    )
}

private fun MediaMetadata.text(key: String): String? =
    getString(key)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Artwork comes free with the metadata, as a bitmap already in memory. This is
 * why a cover can appear before the server has said anything at all.
 */
private fun MediaMetadata.artwork(): Bitmap? =
    getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

/**
 * Every key the player actually set, flattened for the diagnostics panel. This
 * is the point of Phase 0: seeing what each app really sends, rather than what
 * the documentation implies it sends.
 */
private fun MediaMetadata.dump(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (key in keySet()) {
        val short = key.substringAfterLast('.')
        val asText = runCatching { getString(key) }.getOrNull()
        if (!asText.isNullOrBlank()) {
            out[short] = asText
            continue
        }
        val asLong = runCatching { getLong(key) }.getOrNull()
        if (asLong != null && asLong != 0L) {
            out[short] = asLong.toString()
            continue
        }
        val asBitmap = runCatching { getBitmap(key) }.getOrNull()
        if (asBitmap != null) {
            out[short] = "bitmap ${asBitmap.width}x${asBitmap.height}"
        }
    }
    return out
}
