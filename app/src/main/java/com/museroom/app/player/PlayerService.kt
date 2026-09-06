package com.museroom.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSessionService
import com.museroom.app.MainActivity
import com.museroom.app.R

/**
 * The declaration that audio is the point.
 *
 * Android stops processes that look idle, and to the system a player with no
 * service attached looks exactly like an app somebody closed. This is what
 * keeps a track going once Museroom is not the thing on screen.
 *
 * The notification comes free, and that is the reason to use Media3's own
 * service rather than hand-rolling one as `sync/RoomService.kt` had to. A real
 * media session means the shade, the lock screen, a car head unit and the
 * button on a pair of headphones all drive the same player without any of them
 * being taught about Museroom. The room's service predates having a player of
 * our own and still exists; the two meet when the WebView goes.
 *
 * Nothing here holds state. The player is [LocalPlayer], which outlives this
 * service, so a session that is torn down and built again lands on the same
 * track at the same position.
 */
@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // The whole engine rather than only the player, because this service is
        // reachable without the app being on screen. A process rebuilt from a
        // headphone button would otherwise stream a song whose file is already
        // on the phone, and would not record that it was played.
        Playback.attach(this)

        // A channel people can find and turn off by name. Media3 will happily
        // ship one called "default_channel_id", which is what somebody sees in
        // their notification settings when they go looking for what is making
        // noise.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("playback")
                .setChannelName(R.string.playback_channel)
                .build(),
        )

        // Tapping the notification should land in Museroom rather than start a
        // second copy of it, which is what the reorder flag is for.
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, LocalPlayer.exo())
            .setSessionActivity(open)
            .build()
            // Registering it is what makes the notification appear.
            //
            // A session handed back from onGetSession is added for you when
            // something connects a controller to it, and nothing here ever
            // does: Museroom drives the player directly. So the service knew it
            // had a session only in the sense that it could produce one on
            // request, and never watched the player, and never posted anything.
            // Music played, the shade stayed empty, and no error was raised
            // because nothing had gone wrong — nobody had asked.
            .also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Swiping Museroom out of recents.
     *
     * A player that keeps going after the app is gone is a player people cannot
     * find to turn off. If it is paused there is nothing to keep alive, so the
     * service goes with it; if it is playing, the person is listening and meant
     * to leave the app, so it stays.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.removeListener(quiet)
            release()
        }
        session = null
        LocalPlayer.release()
        super.onDestroy()
    }

    /** Placeholder listener so the removal above is symmetric with any future add. */
    private val quiet = object : Player.Listener {}
}
