package com.museroom.app

import com.museroom.app.media.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The extrapolation is what makes a timestamp look live on one message every
 * fifteen seconds, on the listener's phone and on a friend's alike.
 */
class PositionTest {

    private fun track(
        positionMs: Long,
        reportedAt: Long,
        playing: Boolean,
        durationMs: Long = 200_000L,
        speed: Float = 1f,
    ) = NowPlaying(
        packageName = "com.spotify.music",
        sourceLabel = "Spotify",
        isTracked = true,
        sourceTrackId = null,
        title = "Nude",
        artist = "Radiohead",
        album = "In Rainbows",
        durationMs = durationMs,
        reportedPositionMs = positionMs,
        reportedAtElapsed = reportedAt,
        playbackSpeed = if (playing) speed else 0f,
        isPlaying = playing,
        audioContentType = 2,
        artwork = null,
        rawMetadata = emptyMap(),
    )

    @Test
    fun `a playing track advances with the clock`() {
        val t = track(positionMs = 30_000, reportedAt = 1_000_000, playing = true)
        assertEquals(30_000L, t.positionAt(1_000_000))
        assertEquals(35_000L, t.positionAt(1_005_000))
    }

    @Test
    fun `a paused track does not move`() {
        val t = track(positionMs = 30_000, reportedAt = 1_000_000, playing = false)
        assertEquals(30_000L, t.positionAt(1_000_000))
        assertEquals(30_000L, t.positionAt(1_060_000))
    }

    @Test
    fun `playback speed is honoured`() {
        val t = track(positionMs = 0, reportedAt = 1_000_000, playing = true, speed = 1.5f)
        assertEquals(15_000L, t.positionAt(1_010_000))
    }

    @Test
    fun `extrapolation never runs past the end of the track`() {
        val t = track(positionMs = 190_000, reportedAt = 1_000_000, playing = true, durationMs = 200_000)
        assertEquals(200_000L, t.positionAt(1_600_000))
    }

    @Test
    fun `a stale snapshot from before the reported time clamps to zero`() {
        val t = track(positionMs = 1_000, reportedAt = 1_000_000, playing = true)
        assertEquals(0L, t.positionAt(900_000))
    }
}
