package com.museroom.app

import com.museroom.app.data.PlayEventType
import com.museroom.app.media.NowPlaying
import com.museroom.app.tracking.PlaybackDiffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDifferTest {

    private val differ = PlaybackDiffer()
    private val clock = 1_700_000_000_000L

    private fun track(
        title: String = "Nude",
        position: Long,
        reportedAt: Long,
        playing: Boolean = true,
    ) = NowPlaying(
        packageName = "com.spotify.music",
        sourceLabel = "Spotify",
        isTracked = true,
        title = title,
        artist = "Radiohead",
        album = "In Rainbows",
        durationMs = 263_000,
        reportedPositionMs = position,
        reportedAtElapsed = reportedAt,
        playbackSpeed = if (playing) 1f else 0f,
        isPlaying = playing,
        audioContentType = 2,
        artwork = null,
        rawMetadata = emptyMap(),
    )

    @Test
    fun `the first thing seen is a track change`() {
        val events = differ.diff(track(position = 0, reportedAt = 0), clock, 0)
        assertEquals(listOf(PlayEventType.TRACK_CHANGE), events.map { it.type })
    }

    @Test
    fun `steady playback stays quiet between heartbeats`() {
        val t = track(position = 0, reportedAt = 0)
        differ.diff(t, clock, 0)
        assertTrue(differ.diff(t, clock + 5_000, 5_000).isEmpty())
        assertTrue(differ.diff(t, clock + 20_000, 20_000).isEmpty())
    }

    @Test
    fun `a heartbeat lands once the interval passes`() {
        val t = track(position = 0, reportedAt = 0)
        differ.diff(t, clock, 0)
        val events = differ.diff(t, clock + 30_000, 30_000)
        assertEquals(listOf(PlayEventType.HEARTBEAT), events.map { it.type })
    }

    @Test
    fun `pausing and resuming are both reported`() {
        differ.diff(track(position = 0, reportedAt = 0), clock, 0)

        val paused = differ.diff(
            track(position = 10_000, reportedAt = 10_000, playing = false), clock + 10_000, 10_000,
        )
        assertEquals(listOf(PlayEventType.PAUSE), paused.map { it.type })

        val resumed = differ.diff(
            track(position = 10_000, reportedAt = 60_000), clock + 60_000, 60_000,
        )
        assertEquals(listOf(PlayEventType.PLAY), resumed.map { it.type })
    }

    @Test
    fun `a jump the clock cannot explain is a seek`() {
        differ.diff(track(position = 0, reportedAt = 0), clock, 0)
        // Ten seconds later the player says it is two minutes in.
        val events = differ.diff(track(position = 120_000, reportedAt = 10_000), clock + 10_000, 10_000)
        assertEquals(listOf(PlayEventType.SEEK), events.map { it.type })
    }

    @Test
    fun `a new song stops the old one and starts the new`() {
        differ.diff(track(position = 0, reportedAt = 0), clock, 0)
        val events = differ.diff(
            track(title = "Reckoner", position = 0, reportedAt = 60_000), clock + 60_000, 60_000,
        )
        assertEquals(
            listOf(PlayEventType.STOP, PlayEventType.TRACK_CHANGE),
            events.map { it.type },
        )
    }

    @Test
    fun `losing the session reports a stop exactly once`() {
        differ.diff(track(position = 0, reportedAt = 0), clock, 0)
        assertEquals(
            listOf(PlayEventType.STOP),
            differ.diff(null, clock + 10_000, 10_000).map { it.type },
        )
        assertTrue(differ.diff(null, clock + 20_000, 20_000).isEmpty())
    }
}
