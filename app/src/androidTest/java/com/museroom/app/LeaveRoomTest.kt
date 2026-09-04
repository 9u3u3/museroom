package com.museroom.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museroom.app.sync.FollowSession
import com.museroom.app.sync.RoomPlayer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leaving a room.
 *
 * Two seconds of work and four things being torn down at once: a coroutine
 * scope, a WebView, a foreground service holding a media session, and a row on
 * the server saying somebody is still here. Every one of them can be halfway
 * through something when the button is pressed, and none of it is reachable
 * from a unit test — which is how leaving came to take the app down without
 * anybody noticing until it was in somebody's hand.
 *
 * A crash on this path kills the test process, so the test failing is the
 * bug reproducing.
 */
@RunWith(AndroidJUnit4::class)
class LeaveRoomTest {

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        FollowSession.stop()
        scenario?.close()
    }

    /** Leaving something never joined must be as safe as leaving a room. */
    @Test
    fun leavingWithoutHavingJoinedIsSafe() {
        FollowSession.stop()
        FollowSession.stop()
        assertNull(FollowSession.following.value)
    }

    @Test
    fun leavingARoomIsSurvivable() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        FollowSession.start(context, NOBODY, "someone")
        // Long enough for the page to be created, the service to be up and the
        // loops to have gone round a few times.
        SystemClock.sleep(8_000)

        FollowSession.stop()
        SystemClock.sleep(2_000)
        assertNull(FollowSession.following.value)
    }

    /**
     * Pressed twice, because a button that looks like it did nothing gets
     * pressed again.
     */
    @Test
    fun leavingTwiceIsSurvivable() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        FollowSession.start(context, NOBODY, "someone")
        SystemClock.sleep(6_000)
        FollowSession.stop()
        FollowSession.stop()
        SystemClock.sleep(2_000)
        assertNull(FollowSession.following.value)
    }

    /** Joining again straight after leaving, which is what a mis-tap looks like. */
    @Test
    fun leavingAndRejoiningIsSurvivable() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        FollowSession.start(context, NOBODY, "someone")
        SystemClock.sleep(4_000)
        FollowSession.stop()
        FollowSession.start(context, NOBODY, "someone else")
        SystemClock.sleep(4_000)
        FollowSession.stop()
        SystemClock.sleep(2_000)
        assertNull(FollowSession.following.value)
    }

    /**
     * The real shape of it: a page up, a track playing, a service holding a
     * media session, and then the button.
     *
     * Everything above leaves with nothing loaded, which turned out to prove
     * very little. This one has music coming out of it when the button is
     * pressed, which is the only state anybody has ever pressed it in.
     */
    @Test
    fun leavingWhileSomethingIsActuallyPlaying() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        FollowSession.start(context, NOBODY, "someone")
        check(waitFor(90_000) { RoomPlayer.started }) { "the page never came up" }
        check(waitFor(60_000) { RoomPlayer.snapshot.value.ready }) { "the player never appeared" }

        val id = runBlocking { RoomPlayer.search("Passionfruit", "Drake") }
        checkNotNull(id) { "nothing came back for a track that certainly exists" }
        RoomPlayer.load(id, 0)
        check(waitFor(60_000) { RoomPlayer.snapshot.value.playing }) { "it never started" }

        // Held in step by speed, which is the state a correction leaves behind.
        RoomPlayer.setRate(1.04)
        SystemClock.sleep(3_000)

        FollowSession.stop()
        SystemClock.sleep(3_000)
        assertNull(FollowSession.following.value)
        check(!RoomPlayer.snapshot.value.playing) { "it was still playing after leaving" }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(250)
        }
        return condition()
    }

    private companion object {
        /** A well-formed id belonging to nobody, so nothing real is touched. */
        const val NOBODY = "00000000-0000-0000-0000-000000000000"
    }
}
