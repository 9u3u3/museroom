package com.museroom.app

import com.museroom.app.credit.Crediting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a track.
 *
 * Every session used to count, which made the number beside the minutes say
 * nothing: skipping through an album was the fastest way to a large one. The
 * same rule is written twice, here and in the database, so both are pinned
 * here and the SQL is checked against these numbers by hand.
 */
class TrackCountTest {

    private val threeMinutes = 180_000L

    @Test fun `a skip does not count`() {
        assertFalse(Crediting.countsAsATrack(creditedMs = 12_000, durationMs = threeMinutes))
    }

    @Test fun `just under a third does not count`() {
        assertFalse(Crediting.countsAsATrack(creditedMs = 53_000, durationMs = threeMinutes))
    }

    @Test fun `exactly thirty percent counts`() {
        assertTrue(Crediting.countsAsATrack(creditedMs = 54_000, durationMs = threeMinutes))
    }

    @Test fun `a full listen counts`() {
        assertTrue(Crediting.countsAsATrack(creditedMs = threeMinutes, durationMs = threeMinutes))
    }

    /**
     * A short track is easier to qualify on, and that is right: thirty seconds
     * of a ninety second interlude really is a third of it.
     */
    @Test fun `the rule is a share, not a fixed length`() {
        assertTrue(Crediting.countsAsATrack(creditedMs = 30_000, durationMs = 90_000))
        assertFalse(Crediting.countsAsATrack(creditedMs = 30_000, durationMs = 600_000))
    }

    /**
     * Some players never publish a duration. Without one there is no share to
     * take, so the same judgement is made on length alone rather than counting
     * every scrap.
     */
    @Test fun `an unknown length falls back to half a minute`() {
        assertFalse(Crediting.countsAsATrack(creditedMs = 29_999, durationMs = 0))
        assertTrue(Crediting.countsAsATrack(creditedMs = 30_000, durationMs = 0))
    }

    /** A malformed duration must not turn into a free pass either way. */
    @Test fun `a negative length is treated as unknown`() {
        assertFalse(Crediting.countsAsATrack(creditedMs = 5_000, durationMs = -1))
        assertTrue(Crediting.countsAsATrack(creditedMs = 45_000, durationMs = -1))
    }
}
