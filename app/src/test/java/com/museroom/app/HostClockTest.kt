package com.museroom.app

import com.museroom.app.net.RemoteNowPlaying
import com.museroom.app.sync.FollowSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The clock a joiner reads.
 *
 * A host's row says where they were and when, and the rest is arithmetic. The
 * arithmetic used to run whatever the host was doing, so a host who paused at
 * 0:22 appeared on a joiner's phone as a clock climbing towards 0:35, snapping
 * back on each fifteen-second heartbeat, and climbing again. A sawtooth over a
 * track that had stopped.
 */
class HostClockTest {

    private fun host(
        playing: Boolean,
        positionMs: Long = 22_000,
        durationMs: Long = 200_000,
        secondsAgo: Long = 13,
    ) = RemoteNowPlaying(
        title = "Like a Tattoo",
        artist = "Sade",
        durationMs = durationMs,
        positionMs = positionMs,
        isPlaying = playing,
        updatedAt = Instant.now().minusSeconds(secondsAgo).toString(),
    )

    @Test fun `a paused host stays where they paused`() {
        assertEquals(22_000, FollowSession.hostPosition(host(playing = false)))
    }

    /**
     * The heartbeat rewrites the row every fifteen seconds without the
     * position moving, so a projection would reset and climb again. Reading it
     * twice, thirteen seconds apart in message terms, must give one answer.
     */
    @Test fun `a paused host does not saw back and forth`() {
        val first = FollowSession.hostPosition(host(playing = false, secondsAgo = 1))
        val later = FollowSession.hostPosition(host(playing = false, secondsAgo = 14))
        assertEquals(first, later)
    }

    @Test fun `a playing host is carried forward`() {
        val position = FollowSession.hostPosition(host(playing = true, secondsAgo = 10))
        assertTrue("expected roughly 32s, got $position", position in 31_000..33_500)
    }

    /**
     * A row that goes stale near the end would otherwise project past the end
     * of the track and sit there, which is the joiner's clock hanging on the
     * last song after the host has moved on.
     */
    @Test fun `a stale row cannot run past the end of the track`() {
        val position = FollowSession.hostPosition(
            host(playing = true, positionMs = 195_000, durationMs = 200_000, secondsAgo = 40),
        )
        assertEquals(200_000, position)
    }

    /** No duration means no ceiling to clamp to, and guessing one would be worse. */
    @Test fun `an unknown length is left alone`() {
        val position = FollowSession.hostPosition(
            host(playing = true, positionMs = 195_000, durationMs = 0, secondsAgo = 10),
        )
        assertTrue("expected roughly 205s, got $position", position in 204_000..206_500)
    }

    /** A row with no usable stamp is taken at its word rather than guessed at. */
    @Test fun `an unparseable stamp falls back to what was reported`() {
        val row = RemoteNowPlaying(
            title = "Like a Tattoo", positionMs = 22_000, isPlaying = true, updatedAt = "soon",
        )
        assertEquals(22_000, FollowSession.hostPosition(row))
    }
}
