package com.museroom.app

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.media.SourceRegistry
import com.museroom.app.tracking.PlaybackTracker
import com.museroom.app.util.NotificationAccess
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 end to end, on a device: a played track becomes events, and events
 * become minutes somebody could be ranked on.
 */
@RunWith(AndroidJUnit4::class)
class TrackingPipelineTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val db by lazy { MuseroomDatabase.get(context) }

    private lateinit var session: MediaSession

    @Before
    fun setUp() = runBlocking {
        check(NotificationAccess.isGranted(context)) {
            "Grant notification access first: adb shell cmd notification allow_listener " +
                "com.museroom.app/com.museroom.app.listener.MediaListenerService"
        }

        db.dao().clearEvents()
        db.dao().clearSessions()
        context.getSharedPreferences("museroom.tracking", Context.MODE_PRIVATE)
            .edit().clear().commit()

        // Our own package is not a recognised player, so opt it in the way a user
        // would when Museroom spots something it does not know.
        SourceRegistry.get(context).setTracked(context.packageName, true)

        PlaybackTracker.stop()
        instrumentation.runOnMainSync {
            session = MediaSession(context, "MuseroomPipeline")
            session.isActive = true
            NowPlayingRepository.start(context)
            PlaybackTracker.start(context)
        }
    }

    @After
    fun tearDown() {
        if (!::session.isInitialized) return
        instrumentation.runOnMainSync {
            session.isActive = false
            session.release()
        }
        PlaybackTracker.stop()
        SourceRegistry.get(context).setTracked(context.packageName, false)
    }

    @Test
    fun aPlayedTrackBecomesCreditedMinutes() = runBlocking {
        play(title = "Nude", durationMs = 263_000)
        SystemClock.sleep(6_000)

        // A different song is what closes the first one's books.
        play(title = "Reckoner", durationMs = 290_000)

        val credited = awaitCredit("nude|radiohead|131")
        assertTrue(
            "expected a few seconds credited for the first track, got ${credited}ms",
            credited in 2_000..15_000,
        )
    }

    private fun play(title: String, durationMs: Long) {
        instrumentation.runOnMainSync {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Radiohead")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "In Rainbows")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                    .build(),
            )
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .build(),
            )
        }
    }

    private suspend fun awaitCredit(fingerprint: String, timeoutMs: Long = 20_000): Long {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val total = db.dao().sessionsFor(fingerprint).sumOf { it.creditedMs }
            if (total > 0) return total
            SystemClock.sleep(500)
        }
        val events = db.dao().allEvents()
        throw AssertionError(
            "Nothing was credited for $fingerprint. Events recorded: " +
                events.joinToString { "${it.type}@${it.fingerprint}" },
        )
    }
}
