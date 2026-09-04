package com.museroom.app

import com.museroom.app.sync.FollowSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page starting a song nobody asked for.
 *
 * A track ending hands YouTube Music back its own queue, and it begins the
 * next thing it fancies — after a Drake song, another Drake song. The page
 * silences itself the moment that happens; this is the other half, deciding
 * whether the track it strayed from is worth going back to.
 *
 * Going back to a track with two seconds left is how a room thrashed: the
 * copy ends again immediately, the page starts something of its own again,
 * and round it goes, with the listener hearing whatever won each race.
 */
class StrayTrackTest {

    private val fourMinutes = 240_000L

    @Test fun `mid track is worth going back to`() {
        assertTrue(FollowSession.worthReloading(fourMinutes, 90_000))
    }

    @Test fun `the last couple of seconds are not`() {
        assertFalse(FollowSession.worthReloading(fourMinutes, 238_000))
    }

    /** Right on the boundary counts as worth it; the doubt goes to playing. */
    @Test fun `six seconds out is still worth it`() {
        assertTrue(FollowSession.worthReloading(fourMinutes, fourMinutes - 6_000))
    }

    @Test fun `five seconds out is not`() {
        assertFalse(FollowSession.worthReloading(fourMinutes, fourMinutes - 5_000))
    }

    /**
     * Past the end, which a projected position can be. There is nothing left
     * to reload, so this must not read as plenty of time remaining.
     */
    @Test fun `past the end is not worth it`() {
        assertFalse(FollowSession.worthReloading(fourMinutes, fourMinutes + 4_000))
    }

    /** No duration means no end to be near, so there is nothing to give up on. */
    @Test fun `an unknown length is always worth it`() {
        assertTrue(FollowSession.worthReloading(0, 238_000))
        assertTrue(FollowSession.worthReloading(-1, 0))
    }
}
