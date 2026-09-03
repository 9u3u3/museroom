package com.museroom.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.museroom.app.sync.RoomPlayer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gate for listening rooms.
 *
 * Everything about following somebody rests on three claims that cannot be
 * checked by reading: that Museroom's JavaScript reaches the real YouTube Music
 * page, that the page's own search will name a recording, and that handing the
 * player an id starts that recording at the moment we asked for. None of it is
 * a public interface, so this test exists to notice the day it changes.
 *
 * It needs the network and it plays audio, so it is slow by nature.
 */
@RunWith(AndroidJUnit4::class)
class RoomPlayerTest {

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        RoomPlayer.leave()
        scenario?.close()
    }

    /**
     * The first tick of a room, which is where this went wrong in the field.
     *
     * A room asks what to play the moment it starts, before the page can
     * possibly be up. Answering "not found" then is answering the wrong
     * question, and the answer used to be remembered, so the song was never
     * looked up again for as long as the host played it.
     */
    @Test
    fun resolvesATrackAskedForBeforeThePageIsUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // Deliberately no warm-up and no waiting.
        val id = runBlocking { RoomPlayer.search("Say What", "Rampa, Adam Port, &ME") }
        assertNotNull("Nothing came back for a track asked for on a cold page.", id)
        assertEquals("That is not a video id: $id", 11, id!!.length)
    }

    /**
     * Ad breaks are the one interruption a room cannot absorb: the host never
     * pauses, so while an ad runs there is no shared moment to hold. Both
     * routes ad slots arrive by are checked, because closing one is no use.
     */
    @Test
    fun theAdSlotsNeverReachThePlayer() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        RoomPlayer.warmUp()
        assertTrue(
            "The YouTube Music page never finished loading.",
            waitFor(90_000) { RoomPlayer.started },
        )

        assertEquals(
            "The document-start script never ran on the page.",
            "true",
            runBlocking { RoomPlayer.evaluate("window.__museroomAdblock === true") },
        )
        assertEquals(
            "Ad fields survived being parsed from the network.",
            "true",
            runBlocking {
                RoomPlayer.evaluate(
                    "(function(){var o=JSON.parse('{\"adPlacements\":[1],\"adSlots\":[2],\"playerAds\":[3]}');" +
                        "return o.adPlacements===undefined&&o.adSlots===undefined&&o.playerAds===undefined;})()",
                )
            },
        )
        assertEquals(
            "Ad fields baked into the page survived.",
            "true",
            runBlocking {
                RoomPlayer.evaluate(
                    "window.ytInitialPlayerResponse === undefined || " +
                        "(window.ytInitialPlayerResponse.adPlacements === undefined && " +
                        "window.ytInitialPlayerResponse.adSlots === undefined)",
                )
            },
        )
    }

    @Test
    fun findsATrackAndStartsItWhereItWasAsked() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        RoomPlayer.warmUp()

        assertTrue(
            "The YouTube Music page never finished loading.",
            waitFor(90_000) { RoomPlayer.started },
        )
        assertTrue(
            "Museroom's script never found the player.",
            waitFor(60_000) { RoomPlayer.snapshot.value.ready },
        )

        // Search, through the page's own signed-in transport rather than an
        // API key. Retried because the page answers only once it is settled.
        var found: String? = null
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (found == null && SystemClock.elapsedRealtime() < deadline) {
            found = runBlocking { RoomPlayer.search("Blinding Lights", "The Weeknd") }
        }
        assertNotNull("The page's search named no recording.", found)
        val videoId = found!!

        RoomPlayer.load(videoId, START_MS)
        assertTrue(
            "The player never took the track it was handed.",
            waitFor(90_000) { RoomPlayer.snapshot.value.videoId == videoId },
        )
        assertTrue(
            "The track never started playing.",
            waitFor(90_000) { RoomPlayer.snapshot.value.playing },
        )

        // The offset is the whole point: a joiner arrives mid-song, and a
        // player that starts from the beginning has not joined anything.
        assertTrue(
            "Started at ${RoomPlayer.snapshot.value.positionMs}ms, not near ${START_MS}ms.",
            waitFor(30_000) { RoomPlayer.snapshot.value.positionMs > START_MS - 5_000 },
        )
    }

    /** Leaving the app for something else does not stop the music. */
    @Test
    fun keepsPlayingWhileTheAppIsInTheBackground() {
        val opened = ActivityScenario.launch(MainActivity::class.java)
        scenario = opened
        RoomPlayer.warmUp()
        assertTrue(waitFor(90_000) { RoomPlayer.started })

        val id = runBlocking { RoomPlayer.search("Blinding Lights", "The Weeknd") }
        assertNotNull(id)
        RoomPlayer.load(id!!, START_MS)
        assertTrue(waitFor(90_000) { RoomPlayer.snapshot.value.playing })

        val before = RoomPlayer.snapshot.value.positionMs
        opened.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)

        SystemClock.sleep(8_000)
        val after = RoomPlayer.snapshot.value
        assertTrue("The player stopped when the app went to the background.", after.playing)
        assertTrue(
            "Position did not move: ${before}ms then ${after.positionMs}ms.",
            after.positionMs > before + 3_000,
        )
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(500)
        }
        return condition()
    }

    private companion object {
        const val START_MS = 45_000L
    }
}
