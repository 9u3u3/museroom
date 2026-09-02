package com.museroom.app

import com.museroom.app.credit.Crediting
import com.museroom.app.data.PlayEvent
import com.museroom.app.data.PlayEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are the rules a public leaderboard stands on. Each test is a way somebody
 * could claim minutes they did not listen to.
 */
class CreditingTest {

    private val base = 1_700_000_000_000L

    private fun event(
        type: PlayEventType,
        atSeconds: Long,
        position: Long = 0,
        fingerprint: String = "nude|radiohead|131",
        durationMs: Long = 263_000,
        clockSkewMs: Long = 0,
    ) = PlayEvent(
        id = atSeconds,
        type = type,
        fingerprint = fingerprint,
        title = "Nude",
        artist = "Radiohead",
        album = "In Rainbows",
        durationMs = durationMs,
        sourcePackage = "com.spotify.music",
        positionMs = position,
        clockMs = base + atSeconds * 1000 + clockSkewMs,
        elapsedMs = atSeconds * 1000,
    )

    @Test
    fun `a track played straight through credits the time it played`() {
        val sessions = Crediting.sessions(
            listOf(
                event(PlayEventType.TRACK_CHANGE, 0),
                event(PlayEventType.HEARTBEAT, 30),
                event(PlayEventType.HEARTBEAT, 60),
                event(PlayEventType.STOP, 90),
            ),
        )
        assertEquals(1, sessions.size)
        assertEquals(90_000L, sessions.single().creditedMs)
    }

    @Test
    fun `paused time is not credited`() {
        val sessions = Crediting.sessions(
            listOf(
                event(PlayEventType.TRACK_CHANGE, 0),
                event(PlayEventType.HEARTBEAT, 30),
                event(PlayEventType.PAUSE, 40),
                // ten minutes of nothing
                event(PlayEventType.PLAY, 640),
                event(PlayEventType.HEARTBEAT, 670),
                event(PlayEventType.STOP, 680),
            ),
        )
        assertEquals(80_000L, sessions.single().creditedMs)
    }

    @Test
    fun `looping the same ten seconds cannot beat the track length`() {
        // Two hours of events on a four-minute song.
        val events = mutableListOf(event(PlayEventType.TRACK_CHANGE, 0))
        for (t in 30..7_200 step 30) {
            events += event(PlayEventType.HEARTBEAT, t.toLong(), position = 10_000)
            events += event(PlayEventType.SEEK, t.toLong(), position = 0)
        }
        events += event(PlayEventType.STOP, 7_230)

        val credited = Crediting.sessions(events).sumOf { it.creditedMs }
        assertTrue("credited $credited must not exceed the track", credited <= 263_000L)
    }

    @Test
    fun `an unexplained gap is not credited`() {
        val sessions = Crediting.sessions(
            listOf(
                event(PlayEventType.TRACK_CHANGE, 0),
                event(PlayEventType.HEARTBEAT, 30),
                // The process died here. Nothing for an hour, then it comes back.
                event(PlayEventType.HEARTBEAT, 3_630),
                event(PlayEventType.STOP, 3_660),
            ),
        )
        // 30s before the gap, 30s after it, and nothing for the hour in between.
        assertEquals(60_000L, sessions.single().creditedMs)
    }

    @Test
    fun `a faked monotonic clock cannot outrun wall time`() {
        // elapsedMs claims 90 seconds passed; the wall clock only moved 10.
        val events = listOf(
            event(PlayEventType.TRACK_CHANGE, 0),
            event(PlayEventType.HEARTBEAT, 90, clockSkewMs = -80_000),
            event(PlayEventType.STOP, 91, clockSkewMs = -80_000),
        )
        val credited = Crediting.sessions(events).sumOf { it.creditedMs }
        assertTrue("credited $credited should track the slower clock", credited <= 11_000L)
    }

    @Test
    fun `a track change closes one session and opens the next`() {
        val sessions = Crediting.sessions(
            listOf(
                event(PlayEventType.TRACK_CHANGE, 0),
                event(PlayEventType.HEARTBEAT, 30),
                event(PlayEventType.TRACK_CHANGE, 60, fingerprint = "reckoner|radiohead|145"),
                event(PlayEventType.HEARTBEAT, 90, fingerprint = "reckoner|radiohead|145"),
                event(PlayEventType.STOP, 120, fingerprint = "reckoner|radiohead|145"),
            ),
        )
        assertEquals(2, sessions.size)
        assertEquals(60_000L, sessions[0].creditedMs)
        assertEquals(60_000L, sessions[1].creditedMs)
    }

    @Test
    fun `sub-second noise is dropped rather than recorded`() {
        val sessions = Crediting.sessions(
            listOf(
                event(PlayEventType.TRACK_CHANGE, 0),
                event(PlayEventType.STOP, 0),
            ),
        )
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `the daily cap holds`() {
        val day = Crediting.sessions(
            listOf(
                event(PlayEventType.TRACK_CHANGE, 0, durationMs = 0),
                event(PlayEventType.HEARTBEAT, 30, durationMs = 0),
                event(PlayEventType.STOP, 60, durationMs = 0),
            ),
        )
        val inflated = day.map { it.copy(creditedMs = 20 * 60 * 60 * 1000L) }
        val total = Crediting.dailyTotals(inflated).values.single()
        assertEquals(Crediting.DAILY_CAP_MS, total)
    }
}
