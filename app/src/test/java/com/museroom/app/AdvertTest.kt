package com.museroom.app

import android.media.AudioAttributes
import com.museroom.app.media.NowPlaying
import com.museroom.app.media.advertPlaying
import com.museroom.app.media.pickActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An advert on the host's phone.
 *
 * It used to be dropped where it was detected, which left a room unable to
 * tell an advert from somebody putting their phone down — and those want
 * opposite answers. One holds the track and waits; the other lets it go.
 *
 * So the fact of an advert survives while nothing about the advert does. These
 * pin both halves of that: it never becomes something to show or to count, and
 * it is still reachable by the one thing that needs it.
 */
class AdvertTest {

    private fun session(
        advert: Boolean,
        playing: Boolean = true,
        title: String = "Like a Tattoo",
        tracked: Boolean = true,
        atElapsed: Long = 10_000,
    ) = NowPlaying(
        packageName = "com.spotify.music",
        sourceLabel = "Spotify",
        isTracked = tracked,
        isAdvert = advert,
        sourceTrackId = null,
        title = if (advert) "" else title,
        artist = if (advert) "" else "Sade",
        album = "",
        durationMs = if (advert) 0 else 204_000,
        reportedPositionMs = 0,
        reportedAtElapsed = atElapsed,
        playbackSpeed = if (playing) 1f else 0f,
        isPlaying = playing,
        audioContentType = AudioAttributes.CONTENT_TYPE_MUSIC,
        artwork = null,
        rawMetadata = emptyMap(),
    )

    @Test fun `an advert is never the active session`() {
        assertNull(listOf(session(advert = true)).pickActive())
    }

    @Test fun `an advert cannot displace the song under it`() {
        val sessions = listOf(
            session(advert = false, atElapsed = 1_000),
            session(advert = true, atElapsed = 9_000),
        )
        assertEquals("Like a Tattoo", sessions.pickActive()?.title)
    }

    @Test fun `an advert is still reachable by whatever needs to say so`() {
        assertTrue(listOf(session(advert = true)).advertPlaying())
    }

    @Test fun `music is not an advert`() {
        assertFalse(listOf(session(advert = false)).advertPlaying())
    }

    /** A paused advert is not being heard, so there is nothing to wait out. */
    @Test fun `a paused advert is not announced`() {
        assertFalse(listOf(session(advert = true, playing = false)).advertPlaying())
    }

    /**
     * An advert in an app Museroom does not count is nobody's business. It
     * cannot be credited, so it must not be able to stop a room either.
     */
    @Test fun `an advert in an unsupported app is ignored`() {
        assertFalse(listOf(session(advert = true, tracked = false)).advertPlaying())
    }

    /** Nothing about the advert itself may travel: not a title, not a length. */
    @Test fun `an advert carries nothing about itself`() {
        val advert = session(advert = true)
        assertEquals("", advert.title)
        assertEquals("", advert.artist)
        assertEquals(0L, advert.durationMs)
        assertNull(advert.sourceTrackId)
    }
}
