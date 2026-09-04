package com.museroom.app

import com.museroom.app.sync.FollowSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Closing a gap without anybody hearing it happen.
 *
 * Seeking is the obvious answer and the wrong one for small numbers: the music
 * stops, skips and starts again, and doing that every time two phones drift
 * half a second apart is worse than the drift itself. A few per cent of speed
 * is inaudible with the pitch held, and it closes the gap and holds it closed.
 */
class DriftTest {

    /** Positive means the host is ahead, so the joiner has to hurry. */
    @Test fun `behind means play faster`() {
        assertTrue(FollowSession.rateFor(600) > 1.0)
    }

    @Test fun `ahead means play slower`() {
        assertTrue(FollowSession.rateFor(-600) < 1.0)
    }

    /** Chasing the last fraction of a second only means never settling. */
    @Test fun `close enough is left alone`() {
        assertEquals(1.0, FollowSession.rateFor(0), 0.0)
        assertEquals(1.0, FollowSession.rateFor(100), 0.0)
        assertEquals(1.0, FollowSession.rateFor(-100), 0.0)
    }

    /**
     * The whole point is to go unnoticed, so no gap however large may push the
     * speed somewhere a person would hear.
     */
    @Test fun `the nudge is clamped both ways`() {
        for (off in listOf(-60_000L, -3_000L, 3_000L, 60_000L)) {
            val rate = FollowSession.rateFor(off)
            assertTrue("rate $rate for ${off}ms is audible", abs(rate - 1.0) <= 0.0401)
        }
    }

    /** A bigger gap should be closed harder, up to the clamp. */
    @Test fun `a wider gap is chased harder`() {
        assertTrue(FollowSession.rateFor(900) > FollowSession.rateFor(300))
        assertTrue(FollowSession.rateFor(-900) < FollowSession.rateFor(-300))
    }

    /**
     * Half a second, which is the drift people actually notice between two
     * phones, has to close in a time somebody would sit through.
     */
    @Test fun `half a second closes in a handful of seconds`() {
        val rate = FollowSession.rateFor(500)
        val gainPerSecond = (rate - 1.0) * 1000
        val seconds = 500 / gainPerSecond
        assertTrue("takes ${seconds}s to close half a second", seconds in 1.0..20.0)
    }
}
