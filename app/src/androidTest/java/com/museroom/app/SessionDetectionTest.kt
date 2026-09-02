package com.museroom.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museroom.app.media.NowPlaying
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.util.NotificationAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 0's real gate. Publishes a genuine MediaSession and asserts Museroom
 * reads it back through the same operating-system path Spotify and YouTube Music
 * are read through. If this passes, the product's core mechanism works.
 */
@RunWith(AndroidJUnit4::class)
class SessionDetectionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private lateinit var session: MediaSession
    private var startedAtElapsed = 0L

    private val artwork: Bitmap =
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }

    @Before
    fun setUp() {
        check(NotificationAccess.isGranted(context)) {
            "Notification access is required. Grant it with:\n" +
                "adb shell cmd notification allow_listener " +
                "com.museroom.app/com.museroom.app.listener.MediaListenerService"
        }

        instrumentation.runOnMainSync {
            session = MediaSession(context, "MuseroomPhase0")
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Weird Fishes / Arpeggi")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Radiohead - Topic")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "In Rainbows")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 318_000L)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                    .build(),
            )
            startedAtElapsed = SystemClock.elapsedRealtime()
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 30_000L, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .build(),
            )
            session.isActive = true
            NowPlayingRepository.start(context)
        }
    }

    @After
    fun tearDown() {
        // Guarded: when setUp fails, an unguarded teardown throws its own error and
        // buries the one that actually explains the failure.
        if (!::session.isInitialized) return
        instrumentation.runOnMainSync {
            session.isActive = false
            session.release()
        }
    }

    @Test
    fun museroomReadsALiveSession() {
        val track = awaitTrack()

        assertEquals("Weird Fishes / Arpeggi", track.title)
        assertEquals("Radiohead - Topic", track.artist)
        assertEquals("In Rainbows", track.album)
        assertEquals(318_000L, track.durationMs)
        assertTrue("session should report as playing", track.isPlaying)
        assertNotNull("artwork should arrive with the metadata", track.artwork)
    }

    @Test
    fun theRawFieldsAreVisibleForDiagnosis() {
        val track = awaitTrack()
        // Phase 0 exists to show what a player really sends, not what the docs imply.
        assertTrue(track.rawMetadata.containsKey("TITLE"))
        assertTrue(track.rawMetadata.containsKey("DURATION"))
    }

    @Test
    fun theTimestampAdvancesWithoutAskingThePlayer() {
        val track = awaitTrack()
        val first = track.positionAt(SystemClock.elapsedRealtime())

        // No second read from the session. The same snapshot, a later clock.
        val later = track.positionAt(SystemClock.elapsedRealtime() + 5_000)

        assertTrue("position should start near 30s, was $first", first in 29_000..40_000)
        assertEquals(
            "five seconds of clock should be five seconds of track",
            5_000L,
            later - first,
        )
    }

    @Test
    fun theNoisyArtistNameNormalisesToTheRealOne() {
        val track = awaitTrack()
        // "Radiohead - Topic" and a plain "Radiohead" must be one artist, or the
        // leaderboard counts the same song twice.
        assertEquals("weirdfishesarpeggi|radiohead|159", track.fingerprint)
    }

    private fun awaitTrack(timeoutMs: Long = 8_000): NowPlaying {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val match = NowPlayingRepository.sessions.value
                .firstOrNull { it.packageName == context.packageName }
            if (match != null) return match
            SystemClock.sleep(200)
            instrumentation.runOnMainSync { NowPlayingRepository.refresh() }
        }
        throw AssertionError(
            "Museroom never saw the published session. " +
                "Sessions visible: ${NowPlayingRepository.sessions.value.map { it.packageName }}",
        )
    }
}
