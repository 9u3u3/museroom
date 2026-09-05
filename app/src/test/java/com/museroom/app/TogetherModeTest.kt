package com.museroom.app

import android.media.AudioAttributes
import com.museroom.app.media.NowPlaying
import com.museroom.app.media.ROOM_PACKAGE
import com.museroom.app.media.pickActive
import com.museroom.app.net.RemoteNowPlaying
import com.museroom.app.sync.FollowSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The other kind of room, where the host is a client of it.
 *
 * A broadcast room runs three seconds behind on purpose, because the host is
 * playing an app Museroom can only read and a listener told about a song at
 * the instant it starts cannot have fetched it yet. Together mode removes the
 * thing that was ahead: nobody's music app is the speaker, so every phone is
 * waiting on one written moment and there is nothing to run behind.
 *
 * Both bargains are held in the same loop, and the difference between them is
 * a single field on a row. These are the places where reading it wrong would
 * be heard.
 */
class TogetherModeTest {

    private fun host(
        together: Boolean,
        positionMs: Long = 0,
        playing: Boolean = true,
        startsAt: Instant? = null,
        secondsAgo: Long = 0,
        startPositionMs: Long = 0,
    ) = RemoteNowPlaying(
        title = "Nights",
        artist = "Frank Ocean",
        durationMs = 307_000,
        positionMs = positionMs,
        isPlaying = playing,
        updatedAt = Instant.now().minusSeconds(secondsAgo).toString(),
        roomMode = if (together) "together" else "broadcast",
        startsAt = startsAt?.toString(),
        startPositionMs = startPositionMs,
    )

    // ---- how far behind, and why -----------------------------------------

    @Test
    fun `a broadcast room runs behind the host`() {
        assertEquals(3_000L, FollowSession.lagFor(host(together = false)))
    }

    @Test
    fun `a together room runs behind nobody`() {
        // Not a tightened tolerance. There is simply no player ahead of the
        // clock to be behind: the host waited for the same moment everybody
        // else did, so being level is what already happened.
        assertEquals(0L, FollowSession.lagFor(host(together = true)))
    }

    @Test
    fun `walking in on a together room joins it where it is`() {
        val target = FollowSession.targetPosition(host(together = true, positionMs = 90_000))
        assertTrue(
            "expected to join level with the host at 90s, got ${target}ms",
            target in 89_500..90_500,
        )
    }

    @Test
    fun `walking in on a broadcast room still joins it three seconds back`() {
        val target = FollowSession.targetPosition(host(together = false, positionMs = 90_000))
        assertTrue(
            "expected roughly three seconds behind 90s, got ${target}ms",
            target in 86_500..87_500,
        )
    }

    // ---- the moment ------------------------------------------------------

    @Test
    fun `a together room begins on the moment the host wrote down`() {
        // Told, not deduced. The host is choosing when the room lets go rather
        // than already playing, so there is a decision on the row and nothing
        // to reconstruct from where anybody has got to.
        val moment = Instant.now().plusSeconds(3)
        val written = FollowSession.roomStartMoment(
            host(together = true, playing = false, startsAt = moment),
        )
        assertEquals(moment.toEpochMilli(), written)
    }

    @Test
    fun `a broadcast room works the moment out for itself`() {
        // Nothing is sent, so nobody is late by however long a message took.
        // The track began at the difference between the position and when that
        // was true, and every phone reading the row arrives at the same number.
        val takenAt = Instant.now()
        val moment = FollowSession.roomStartMoment(
            RemoteNowPlaying(
                title = "Nights",
                artist = "Frank Ocean",
                durationMs = 307_000,
                positionMs = 10_000,
                isPlaying = true,
                updatedAt = takenAt.toString(),
            ),
        )
        assertEquals(takenAt.toEpochMilli() - 10_000 + 3_000 + 2_500, moment)
    }

    @Test
    fun `a track carried across when the mode is turned on is not rewound`() {
        // The one song that does not begin at its beginning. A host who flips
        // the switch ninety seconds into something is handing the room the
        // song they are playing, not starting it again — and for a room that
        // already had listeners in it, starting again would drag every one of
        // them back to a beginning they heard a minute ago.
        val carried = host(
            together = true,
            playing = false,
            startsAt = Instant.now().plusSeconds(3),
            startPositionMs = 90_000,
        )
        assertEquals(90_000L, FollowSession.startFrom(carried))
    }

    @Test
    fun `every other song begins at its beginning`() {
        assertEquals(0L, FollowSession.startFrom(host(together = true, positionMs = 60_000)))
    }

    @Test
    fun `a broadcast room never takes a start position from the row`() {
        // It works out where to be from the position and the clock. A number
        // left on the column by the other mode is not an instruction to it.
        val stale = host(together = false, positionMs = 60_000, startPositionMs = 90_000)
        assertEquals(0L, FollowSession.startFrom(stale))
    }

    // ---- silence that is not a stop --------------------------------------

    @Test
    fun `a host holding a cued track has not stopped`() {
        // The few seconds where a together room is fetching. The truthful
        // reading of the row is "not playing", and acting on it would send
        // every listener away from a song that is about to start.
        val cued = host(
            together = true,
            playing = false,
            startsAt = Instant.now().plusSeconds(3),
        )
        assertTrue(FollowSession.starting(cued))
        assertFalse(FollowSession.saysStop(cued))
    }

    @Test
    fun `a host whose row has not caught up yet has not stopped either`() {
        // The other side of the same moment. Every phone let go at once, and
        // the host's own "playing" still has to be written and pushed; until
        // it lands the row says not playing at position zero, and acting on
        // that would pause a room in the first second of a song.
        val begun = host(
            together = true,
            playing = false,
            startsAt = Instant.now().minusMillis(900),
        )
        assertTrue(FollowSession.starting(begun))
        assertFalse(FollowSession.saysStop(begun))
    }

    @Test
    fun `a together host who actually paused has stopped`() {
        val paused = host(
            together = true,
            positionMs = 42_000,
            playing = false,
            startsAt = Instant.now().minusSeconds(40),
        )
        assertFalse(FollowSession.starting(paused))
        assertTrue(FollowSession.saysStop(paused))
    }

    @Test
    fun `a broadcast host who is not playing is always a stop`() {
        // Whatever else is on the row. Only together mode ever holds a track
        // silent, because only there does Museroom own both ends of it.
        val quiet = host(
            together = false,
            playing = false,
            startsAt = Instant.now().plusSeconds(3),
        )
        assertFalse(FollowSession.starting(quiet))
        assertTrue(FollowSession.saysStop(quiet))
    }

    @Test
    fun `a paused together host is not projected forward`() {
        // The same rule that stopped a listener's clock climbing past a host
        // who had put their phone down. A stopped player is where it was left.
        val paused = host(together = true, positionMs = 42_000, playing = false, secondsAgo = 20)
        assertEquals(42_000L, FollowSession.hostPosition(paused))
    }

    // ---- whose player is the room ----------------------------------------

    private fun session(
        packageName: String,
        title: String,
        playing: Boolean,
        atElapsed: Long,
    ) = NowPlaying(
        packageName = packageName,
        sourceLabel = packageName,
        isTracked = true,
        sourceTrackId = null,
        title = title,
        artist = "Frank Ocean",
        album = "",
        durationMs = 307_000,
        reportedPositionMs = 1_000,
        reportedAtElapsed = atElapsed,
        playbackSpeed = 1f,
        isPlaying = playing,
        audioContentType = AudioAttributes.CONTENT_TYPE_MUSIC,
        artwork = null,
        rawMetadata = emptyMap(),
    )

    @Test
    fun `the room wins over a music app that never stopped`() {
        /*
         * Museroom asks the app it is taking over from to pause and cannot
         * make it, so a together host may well still have Spotify running.
         * Deciding between the two on recency would mean whichever reported
         * last, and the row every listener steers by would flick between two
         * different songs — one of them three seconds ahead of where anybody
         * following could possibly be.
         */
        val active = listOf(
            session("com.spotify.music", "Solo", playing = true, atElapsed = 99_000),
            session(ROOM_PACKAGE, "Nights", playing = true, atElapsed = 10_000),
        ).pickActive()
        assertEquals("Nights", active?.title)
    }

    @Test
    fun `the room wins even while it is holding a cued track silent`() {
        // The gap between songs is the moment this matters most: the room is
        // silent for a few seconds by design, and that is exactly when a music
        // app still playing in the background would otherwise take the row.
        val active = listOf(
            session("com.spotify.music", "Solo", playing = true, atElapsed = 99_000),
            session(ROOM_PACKAGE, "Nights", playing = false, atElapsed = 99_500),
        ).pickActive()
        assertEquals("Nights", active?.title)
    }
}
