package com.museroom.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.museroom.app.net.ServerClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The clock two phones agree on.
 *
 * A room is two players holding the same moment, and neither phone's own idea
 * of the time is good enough to build that on: two Android phones are
 * routinely most of a second apart without either being broken. This asks the
 * real database over the real network, because the accuracy that matters is
 * the accuracy through a phone's actual connection, and nothing offline can
 * tell you what that is.
 */
@RunWith(AndroidJUnit4::class)
class ServerClockTest {

    @Test
    fun theServerWillSayWhatTimeItIs() {
        assertTrue("the clock could not be read at all", runBlocking { ServerClock.sync(force = true) })
        assertTrue("nothing was measured", ServerClock.measured)
    }

    /**
     * The answer has to be usable, not merely present. An emulator's clock is
     * kept close to the host's, so anything wild here means the arithmetic is
     * wrong rather than that the clock is.
     */
    @Test
    fun theOffsetIsPlausible() {
        runBlocking { ServerClock.sync(force = true) }
        val offset = ServerClock.offsetMs
        assertTrue("offset of ${offset}ms is not a clock difference", abs(offset) < 60_000)
    }

    /** Asking repeatedly must converge rather than wander. */
    @Test
    fun askingAgainDoesNotMoveTheAnswerMuch() {
        runBlocking { ServerClock.sync(force = true) }
        val first = ServerClock.offsetMs
        repeat(3) { runBlocking { ServerClock.sync(force = true) } }
        val drift = abs(ServerClock.offsetMs - first)
        assertTrue("four readings disagreed by ${drift}ms", drift < 2_000)
    }

    /** And the corrected clock has to actually be a clock. */
    @Test
    fun theCorrectedClockRuns() {
        runBlocking { ServerClock.sync(force = true) }
        val before = ServerClock.nowMs()
        Thread.sleep(1_200)
        val after = ServerClock.nowMs()
        assertTrue("it did not advance", after - before in 900..1_500)
    }
}
