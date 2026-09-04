package com.museroom.app

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museroom.app.media.NowPlaying
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.sync.RoomService
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The room's own notification, which nothing else can reach.
 *
 * A joiner's music comes out of Museroom, so Museroom posts the media
 * notification for it — with a real media session behind it so the system
 * draws the cover and the seek bar. That is a lot of platform surface for
 * something no unit test can touch: a session, a style that reads it, actions,
 * a bitmap, and a service that has to tear all of it down again.
 *
 * A crash anywhere in there kills the test process, so a failure here is the
 * bug reproducing.
 */
@RunWith(AndroidJUnit4::class)
class RoomNotificationTest {

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        NowPlayingRepository.setRoomPlayback(null)
        InstrumentationRegistry.getInstrumentation().targetContext.let { RoomService.stop(it) }
        scenario?.close()
    }

    private fun track(art: Bitmap?) = NowPlaying(
        packageName = "com.museroom.app",
        sourceLabel = "Museroom room",
        isTracked = true,
        sourceTrackId = "dQw4w9WgXcQ",
        title = "GREECE (feat. Drake)",
        artist = "DJ Khaled",
        album = "",
        durationMs = 219_000,
        reportedPositionMs = 204_000,
        reportedAtElapsed = SystemClock.elapsedRealtime(),
        playbackSpeed = 1f,
        isPlaying = true,
        audioContentType = AudioAttributes.CONTENT_TYPE_MUSIC,
        artwork = art,
        rawMetadata = emptyMap(),
    )

    /** With a cover, which is the shape it takes in anybody's hand. */
    @Test
    fun postingAndTearingDownTheRoomNotification() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        RoomService.start(context, "harsh")
        SystemClock.sleep(2_000)

        val art = Bitmap.createBitmap(544, 544, Bitmap.Config.ARGB_8888).apply { eraseColor(-0x555556) }
        NowPlayingRepository.setRoomPlayback(track(art))
        SystemClock.sleep(3_000)

        // A skip, so the notification is rebuilt rather than only created.
        NowPlayingRepository.setRoomPlayback(track(art).copy(title = "One Dance"))
        SystemClock.sleep(2_000)

        // And the way out, which is where somebody reported losing the app.
        NowPlayingRepository.setRoomPlayback(null)
        RoomService.stop(context)
        SystemClock.sleep(2_000)
    }

    /** And without one, because a cover is looked up and can simply not arrive. */
    @Test
    fun theRoomNotificationSurvivesHavingNoCover() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        RoomService.start(context, "harsh")
        NowPlayingRepository.setRoomPlayback(track(null))
        SystemClock.sleep(3_000)
        RoomService.stop(context)
        SystemClock.sleep(1_500)
    }
}
