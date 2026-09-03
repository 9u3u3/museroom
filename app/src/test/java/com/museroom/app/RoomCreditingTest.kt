package com.museroom.app

import android.media.AudioAttributes
import com.museroom.app.media.NowPlaying
import com.museroom.app.media.pickActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time spent in somebody else's room is listening, and used to count for
 * nothing: a room plays through a browser view Museroom owns, which is not a
 * media session anybody can read, so an hour of it looked exactly like an hour
 * of silence. It is reported as a session now — which means the rules that keep
 * the leaderboard honest have to hold for it too.
 */
class RoomCreditingTest {

    private fun room(
        playing: Boolean,
        title: String = "Like a Tattoo",
        atElapsed: Long = 10_000,
    ) = NowPlaying(
        packageName = "com.museroom.app",
        sourceLabel = "Museroom room",
        isTracked = true,
        sourceTrackId = "abcdefghijk",
        title = title,
        artist = "Sade",
        album = "",
        durationMs = 204_000,
        reportedPositionMs = 45_000,
        reportedAtElapsed = atElapsed,
        playbackSpeed = 1f,
        isPlaying = playing,
        audioContentType = AudioAttributes.CONTENT_TYPE_MUSIC,
        artwork = null,
        rawMetadata = emptyMap(),
    )

    private fun realPlayer(playing: Boolean, atElapsed: Long) = NowPlaying(
        packageName = "com.spotify.music",
        sourceLabel = "Spotify",
        isTracked = true,
        sourceTrackId = null,
        title = "Nude",
        artist = "Radiohead",
        album = "In Rainbows",
        durationMs = 263_000,
        reportedPositionMs = 1_000,
        reportedAtElapsed = atElapsed,
        playbackSpeed = 1f,
        isPlaying = playing,
        audioContentType = AudioAttributes.CONTENT_TYPE_MUSIC,
        artwork = null,
        rawMetadata = emptyMap(),
    )

    @Test
    fun `a room counts as something being played`() {
        val active = listOf(room(playing = true)).pickActive()
        assertEquals("Like a Tattoo", active?.title)
    }

    @Test
    fun `the room's own package is never counted from a real session`() {
        // The room session is one Museroom builds, never one it reads. If our
        // own package were on the allowlist, anybody's copy of Museroom would
        // become a player other people could be recorded through.
        assertTrue(!com.museroom.app.media.Sources.isSupported("com.museroom.app"))
    }

    @Test
    fun `a room that is playing wins over a player that is paused`() {
        val active = listOf(
            realPlayer(playing = false, atElapsed = 99_000),
            room(playing = true, atElapsed = 10_000),
        ).pickActive()
        assertEquals("Like a Tattoo", active?.title)
    }

    @Test
    fun `nothing is active once the room is cleared`() {
        assertNull(emptyList<NowPlaying>().pickActive())
    }

    @Test
    fun `a room position advances by the clock, so silence must never be left behind`() {
        // This is the shape of the bug worth guarding: a session left in place
        // while nothing is audible keeps advancing on arithmetic alone, and
        // every second of it would be credited. The follow loop clears the
        // room whenever it is not in step; this asserts why that matters.
        val stale = room(playing = true, atElapsed = 0)
        val tenSecondsLater = stale.positionAt(10_000)
        assertEquals(55_000, tenSecondsLater)

        val paused = room(playing = false, atElapsed = 0)
        assertEquals(45_000, paused.positionAt(10_000))
    }
}
