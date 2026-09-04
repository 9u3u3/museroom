package com.museroom.app

import com.museroom.app.net.RemoteNowPlaying
import com.museroom.app.sync.FollowSession
import com.museroom.app.sync.RoomStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Beginning a song at an agreed moment rather than whenever each phone
 * finished fetching it.
 *
 * The two halves worth pinning are the ones nobody can see from the outside.
 * A schedule read wrongly restarts a song somebody is halfway through, and a
 * wait that adapts the wrong way round either makes everybody sit through a
 * gap they do not need or hands them a moment they cannot meet.
 */
class StartTogetherTest {

    @Before
    fun clean() = RoomStart.reset()

    private fun row(startsAt: String?) = RemoteNowPlaying(
        title = "Passionfruit",
        artist = "Drake",
        durationMs = 298_000,
        positionMs = 400,
        isPlaying = false,
        updatedAt = Instant.now().toString(),
        startsAt = startsAt,
        startPositionMs = 400,
    )

    // ---------------------------------------------------------- the moment --

    @Test
    fun `a row with no schedule is not one`() {
        assertEquals(0L, FollowSession.startsAtMs(row(null)))
        assertEquals(0L, FollowSession.startsAtMs(row("")))
    }

    @Test
    fun `a moment still to come is read back`() {
        val at = Instant.now().plusSeconds(2)
        val read = FollowSession.startsAtMs(row(at.toString()))
        assertTrue(
            "expected about ${at.toEpochMilli()}, got $read",
            Math.abs(read - at.toEpochMilli()) < 2_000,
        )
    }

    /**
     * A schedule from a song that has been running for a minute is not
     * something anybody is still waiting on. Acting on it would stop a track
     * somebody is halfway through and start it again from the beginning.
     */
    @Test
    fun `a moment long past is forgotten`() {
        val old = Instant.now().minusSeconds(120).toString()
        assertEquals(0L, FollowSession.startsAtMs(row(old)))
    }

    /** Rubbish in the column is not a reason to stop following somebody. */
    @Test
    fun `an unreadable stamp is simply no schedule`() {
        assertEquals(0L, FollowSession.startsAtMs(row("not a time")))
    }

    // ------------------------------------------------- turning up or not --

    /**
     * The bug that looked like magic: a room joined mid-song came right the
     * instant the host passed thirty seconds, because that was when the stale
     * schedule was finally forgotten. Until then the joiner was handed the top
     * of a track everybody else was well into.
     */
    @Test
    fun `a moment that has already gone is not turned up for`() {
        assertTrue(!FollowSession.worthMeeting(-15_000, committed = false))
        assertTrue(!FollowSession.worthMeeting(-29_000, committed = false))
    }

    @Test
    fun `a moment still to come is`() {
        assertTrue(FollowSession.worthMeeting(2_000, committed = false))
        assertTrue(FollowSession.worthMeeting(50, committed = false))
    }

    /** Being a touch late to a moment is not a reason to abandon it. */
    @Test
    fun `being slightly late still counts`() {
        assertTrue(FollowSession.worthMeeting(-200, committed = false))
    }

    /**
     * Once the track is fetched and waiting, lateness stops mattering: the
     * thing in hand is the right thing, and starting it beats loading it again.
     */
    @Test
    fun `a track already fetched is started however late we are`() {
        assertTrue(FollowSession.worthMeeting(-20_000, committed = true))
    }

    // ---------------------------------------------------------- projecting --

    /**
     * A phone that stopped saying anything is not evidence of a position. Run
     * the arithmetic over a minute of silence and the track is put down
     * somewhere nobody is, which is a song starting from the middle and then
     * jumping back.
     */
    @Test
    fun `a reading is not carried forward forever`() {
        val ancient = RemoteNowPlaying(
            title = "Passionfruit",
            artist = "Drake",
            durationMs = 298_000,
            positionMs = 10_000,
            isPlaying = true,
            updatedAt = Instant.now().minusSeconds(120).toString(),
        )
        val where = FollowSession.hostPosition(ancient)
        assertTrue(
            "projected to ${where}ms from a two-minute-old reading",
            where <= 10_000 + 20_000,
        )
    }

    @Test
    fun `a fresh reading is carried forward normally`() {
        val recent = RemoteNowPlaying(
            title = "Passionfruit",
            artist = "Drake",
            durationMs = 298_000,
            positionMs = 10_000,
            isPlaying = true,
            updatedAt = Instant.now().minusSeconds(5).toString(),
        )
        val where = FollowSession.hostPosition(recent)
        assertTrue("expected about 15s, got ${where}ms", where in 14_000..16_500)
    }

    // ------------------------------------------------------------ the wait --

    @Test
    fun `a room nobody could keep up with waits longer`() {
        val before = RoomStart.waitMs
        RoomStart.adapt(listOf(-200, 900))
        assertTrue(
            "waiting ${RoomStart.waitMs} should exceed $before after somebody was 900ms late",
            RoomStart.waitMs > before + 900,
        )
    }

    /** The worst listener sets the wait. A room is only as quick as its slowest. */
    @Test
    fun `the slowest listener is the one that counts`() {
        RoomStart.adapt(listOf(1_500, 10, -400))
        val slowest = RoomStart.waitMs
        RoomStart.reset()
        RoomStart.adapt(listOf(1_500))
        assertEquals(slowest, RoomStart.waitMs)
    }

    @Test
    fun `a room that keeps arriving early stops waiting so long`() {
        val before = RoomStart.waitMs
        RoomStart.adapt(listOf(-1_400, -900))
        assertTrue("expected less than $before, got ${RoomStart.waitMs}", RoomStart.waitMs < before)
    }

    /**
     * Down slowly and up fast, on purpose. Being late is heard as a song
     * starting without you; being early is heard as nothing at all.
     */
    @Test
    fun `it slackens faster than it tightens`() {
        val start = RoomStart.waitMs
        RoomStart.adapt(listOf(-1_400))
        val downOnce = start - RoomStart.waitMs
        RoomStart.reset()
        RoomStart.adapt(listOf(800))
        val upOnce = RoomStart.waitMs - start
        assertTrue("down $downOnce should be gentler than up $upOnce", downOnce < upOnce)
    }

    @Test
    fun `it never waits forever and never stops waiting`() {
        repeat(20) { RoomStart.adapt(listOf(5_000)) }
        assertTrue("waiting ${RoomStart.waitMs} is too long", RoomStart.waitMs <= 6_000)
        repeat(60) { RoomStart.adapt(listOf(-5_000)) }
        assertTrue("waiting ${RoomStart.waitMs} is not a wait", RoomStart.waitMs >= 900)
    }

    /**
     * A listener who has never been given a start has nothing to say, and
     * silence must not read as "ready instantly".
     */
    @Test
    fun `a listener with nothing to report changes nothing`() {
        val before = RoomStart.waitMs
        RoomStart.adapt(listOf(null, null))
        assertEquals(before, RoomStart.waitMs)
    }

    // -------------------------------------------------- whether to bother --

    @Test
    fun `a host listening alone is never held`() {
        assertTrue(!RoomStart.worthHolding(track(), listeners = 0))
        assertTrue(RoomStart.worthHolding(track(), listeners = 1))
    }

    @Test
    fun `an advert is not worth a gap`() {
        assertTrue(!RoomStart.worthHolding(track(advert = true), listeners = 2))
    }

    @Test
    fun `a player that is not playing is not starting anything`() {
        assertTrue(!RoomStart.worthHolding(track(playing = false), listeners = 2))
        assertTrue(!RoomStart.worthHolding(null, listeners = 2))
    }

    private fun track(
        playing: Boolean = true,
        advert: Boolean = false,
    ) = com.museroom.app.media.NowPlaying(
        packageName = "com.spotify.music",
        sourceLabel = "Spotify",
        isTracked = true,
        isAdvert = advert,
        sourceTrackId = null,
        title = "Passionfruit",
        artist = "Drake",
        album = "More Life",
        durationMs = 298_000,
        reportedPositionMs = 400,
        reportedAtElapsed = 0,
        playbackSpeed = 1f,
        isPlaying = playing,
        audioContentType = 2,
        artwork = null,
        rawMetadata = emptyMap(),
    )
}
