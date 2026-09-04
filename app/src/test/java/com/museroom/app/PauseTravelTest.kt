package com.museroom.app

import com.museroom.app.net.RemoteNowPlaying
import com.museroom.app.sync.FollowSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A host pausing, heard at once.
 *
 * The push arrives under a second after they press it. Acting on it used to
 * wait for the follow loop's next look, so the joiner played on for a couple
 * of seconds and then stopped for no visible reason.
 *
 * Only what a single row settles on its own belongs here. Anything needing
 * both sides of the story stays with the loop, which is the one place that
 * knows what our own player is doing.
 */
class PauseTravelTest {

    private fun row(
        playing: Boolean = true,
        advert: Boolean = false,
        title: String = "Passionfruit",
    ) = RemoteNowPlaying(
        title = title,
        artist = "Drake",
        durationMs = 299_000,
        positionMs = 135_000,
        isPlaying = playing,
        isAdvert = advert,
    )

    @Test fun `they paused, so we stop`() {
        assertTrue(FollowSession.saysStop(row(playing = false)))
    }

    @Test fun `an advert at their end stops us too`() {
        assertTrue(FollowSession.saysStop(row(advert = true)))
    }

    /** An advert row carries no track, and a row with no track is not music. */
    @Test fun `a row with nothing in it stops us`() {
        assertTrue(FollowSession.saysStop(row(title = "")))
    }

    @Test fun `a playing row does not`() {
        assertFalse(FollowSession.saysStop(row()))
    }

    /**
     * Starting again is not settled by the row alone: it says nothing about
     * whether the right track is even loaded here. That decision stays with
     * the loop, so a playing row must never be reason on its own to stop.
     */
    @Test fun `resuming is left to the loop`() {
        assertFalse(FollowSession.saysStop(row(playing = true, advert = false)))
    }
}
