package com.museroom.app

import com.museroom.app.net.RemoteNowPlaying
import com.museroom.app.sync.FollowSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * A room that runs a set distance behind the host.
 *
 * Everything that ever went wrong at the start of a song came from trying to
 * be level with somebody who is told about the song at the instant it begins
 * and cannot possibly have fetched it yet. Being level meant either starting
 * late and skipping the difference or being hauled forward once the
 * difference was noticed, and both cost a listener the opening of a track.
 *
 * Running behind on purpose turns that gap into a budget. The seconds are
 * spent before the song starts instead of during it, so nothing is late and
 * nothing has to be skipped.
 */
class RoomDelayTest {

    private fun host(
        positionMs: Long,
        playing: Boolean = true,
        secondsAgo: Long = 0,
        durationMs: Long = 298_000,
    ) = RemoteNowPlaying(
        title = "Passionfruit",
        artist = "Drake",
        durationMs = durationMs,
        positionMs = positionMs,
        isPlaying = playing,
        updatedAt = Instant.now().minusSeconds(secondsAgo).toString(),
    )

    @Test
    fun `the room aims behind the host, not at them`() {
        val target = FollowSession.targetPosition(host(positionMs = 60_000))
        assertTrue(
            "expected roughly three seconds behind 60s, got ${target}ms",
            target in 56_500..57_500,
        )
    }

    /**
     * The distance is constant, which is the only reason it is inaudible. A
     * delay that varied would be a delay somebody could hear being corrected.
     */
    @Test
    fun `the distance does not change with the position`() {
        val early = 20_000 - FollowSession.targetPosition(host(positionMs = 20_000))
        val late = 200_000 - FollowSession.targetPosition(host(positionMs = 200_000))
        assertEquals(early, late)
    }

    /**
     * The first seconds of a song are the case the whole delay exists for.
     * There is nothing behind the start of a track, so the target sits at the
     * start rather than going negative and asking for a position that is not
     * on the record.
     */
    @Test
    fun `the beginning of a track is as far back as it goes`() {
        assertEquals(0L, FollowSession.targetPosition(host(positionMs = 0)))
        assertEquals(0L, FollowSession.targetPosition(host(positionMs = 800)))
        assertEquals(0L, FollowSession.targetPosition(host(positionMs = 2_500)))
    }

    /**
     * A paused host is where they stopped, and the room sits its usual
     * distance behind that. Everybody stops where they were rather than
     * lurching to meet a position nobody is moving towards.
     */
    @Test
    fun `a paused host is still followed at the same distance`() {
        val target = FollowSession.targetPosition(host(positionMs = 45_000, playing = false))
        assertEquals(42_000L, target)
    }

    /**
     * A reading carried forward too far invents a position, and a track put
     * down there is a song starting from the middle. Twenty seconds is the
     * most any single reading is trusted for.
     */
    @Test
    fun `an old reading is not carried forward forever`() {
        val stale = FollowSession.targetPosition(host(positionMs = 10_000, secondsAgo = 120))
        assertTrue("projected to ${stale}ms from a two-minute-old reading", stale <= 27_000)
    }

    @Test
    fun `a fresh reading is carried forward normally`() {
        val recent = FollowSession.hostPosition(host(positionMs = 10_000, secondsAgo = 5))
        assertTrue("expected about 15s, got ${recent}ms", recent in 14_000..16_500)
    }

    // ------------------------------------------------- starting together --

    /**
     * Every phone works the moment out for itself, from the host's own row, so
     * two of them holding the same row arrive at the same answer without
     * anybody sending anything. That is what makes a room start together
     * rather than each phone beginning whenever its own download finished.
     */
    @Test
    fun `two phones reading the same row agree on the moment`() {
        val row = host(positionMs = 1_200, secondsAgo = 0)
        assertEquals(FollowSession.roomStartMoment(row), FollowSession.roomStartMoment(row))
    }

    /**
     * The moment is a property of the song, not of when it was asked about. A
     * later reading of the same song has to name the same instant, or a phone
     * that read it twice would move the goalposts on itself.
     */
    @Test
    fun `a later reading of the same song names the same moment`() {
        val began = Instant.now().minusSeconds(4)
        val early = RemoteNowPlaying(
            title = "Passionfruit", artist = "Drake", durationMs = 298_000,
            positionMs = 1_000, isPlaying = true,
            updatedAt = began.plusMillis(1_000).toString(),
        )
        val later = RemoteNowPlaying(
            title = "Passionfruit", artist = "Drake", durationMs = 298_000,
            positionMs = 3_500, isPlaying = true,
            updatedAt = began.plusMillis(3_500).toString(),
        )
        val drift = Math.abs(
            FollowSession.roomStartMoment(early) - FollowSession.roomStartMoment(later),
        )
        assertTrue("the moment moved by ${drift}ms between readings", drift < 50)
    }

    /**
     * It has to sit past the point the previous track's tail runs out, or the
     * room would be asked to start a song while it is still finishing the last
     * one — and one player cannot do both.
     */
    @Test
    fun `the moment leaves room for the tail and the fetch`() {
        val began = Instant.now()
        val row = RemoteNowPlaying(
            title = "Passionfruit", artist = "Drake", durationMs = 298_000,
            positionMs = 0, isPlaying = true, updatedAt = began.toString(),
        )
        val after = FollowSession.roomStartMoment(row) - began.toEpochMilli()
        assertTrue("only ${after}ms after the song began", after >= 3_000)
    }

    /** A row nobody can read is a reason to start when ready, not to wait. */
    @Test
    fun `an unreadable row means no moment at all`() {
        val broken = RemoteNowPlaying(
            title = "Passionfruit", artist = "Drake", durationMs = 298_000,
            positionMs = 0, isPlaying = true, updatedAt = "not a time",
        )
        assertEquals(0L, FollowSession.roomStartMoment(broken))
    }

    /** How old a reading is, which is what decides whether to trust it. */
    @Test
    fun `the age of a reading is measured`() {
        assertTrue(FollowSession.ageOf(host(positionMs = 0, secondsAgo = 30)) in 29_000..31_500)
        assertTrue(FollowSession.ageOf(host(positionMs = 0, secondsAgo = 0)) < 2_000)
    }

    @Test
    fun `a reading nobody can read is infinitely old`() {
        val broken = RemoteNowPlaying(
            title = "Passionfruit", artist = "Drake",
            durationMs = 298_000, positionMs = 1_000,
            isPlaying = true, updatedAt = "not a time",
        )
        assertEquals(Long.MAX_VALUE, FollowSession.ageOf(broken))
    }
}
