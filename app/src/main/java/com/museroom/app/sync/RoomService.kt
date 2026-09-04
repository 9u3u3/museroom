package com.museroom.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.museroom.app.MainActivity
import com.museroom.app.R
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.net.LikesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps a room playing when Museroom is not the app on screen, and puts it in
 * the shade where music belongs.
 *
 * Android will happily stop a process that looks idle, and to the system a
 * WebView playing music looks exactly like a WebView. This service is the
 * declaration that audio is the point.
 *
 * The notification is the other half. A host already has one — their own music
 * app posted it — but a joiner's track comes out of Museroom, so without this
 * there was nothing in the shade at all and no way to see what was playing
 * without opening the app. It is a media notification with a real session
 * behind it, so the system draws the cover and the seek bar itself.
 *
 * There is nothing to press. Pause, skip and scrub all belong to the host, and
 * offering a joiner a button that would silently do nothing is worse than not
 * offering it. What is left is the one thing a joiner does decide: whether
 * they liked it.
 */
class RoomService : Service() {

    private var session: MediaSession? = null
    private var scope: CoroutineScope? = null
    private var handle: String = ""

    /** Whether the debt to the system has been paid for this instance. */
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "Museroom").apply { isActive = true }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before anything else, always.
        //
        // A service started as a foreground service owes the system a
        // notification within seconds of every start, and the system kills the
        // process for the debt rather than for the work. The two button
        // actions used to return before paying it, which is fine right up
        // until the one time this instance is not already in the foreground —
        // the process having been killed while the notification stayed in the
        // shade, and somebody then pressing Leave on it. That is a crash on
        // the way out of a room, and it costs nothing to make impossible.
        if (!foreground) {
            handle = intent?.getStringExtra(EXTRA_HANDLE) ?: handle
            startInForeground(build(NowPlayingRepository.room.value, FollowSession.following.value))
        }

        when (intent?.action) {
            ACTION_LEAVE -> {
                FollowSession.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LIKE -> {
                like()
                return START_NOT_STICKY
            }
        }
        intent?.getStringExtra(EXTRA_HANDLE)?.let { handle = it }
        watch()
        return START_NOT_STICKY
    }

    /**
     * Redraws as the room changes.
     *
     * Track, cover and state all arrive from the follow loop rather than from
     * whoever started the service, so the notification keeps up with a host
     * who skips without anybody having to remember to tell it.
     */
    private fun watch() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        newScope.launch {
            combine(NowPlayingRepository.room, FollowSession.following) { track, room ->
                track to room
            }.collect { (track, room) ->
                if (room == null) return@collect
                handle = room.handle
                runCatching { notifier().notify(ID, build(track, room)) }
            }
        }
        // The seek bar is drawn from the position the session last reported,
        // so it needs telling occasionally or it stalls under a track that is
        // still playing.
        newScope.launch {
            while (true) {
                delay(5_000)
                val room = FollowSession.following.value ?: continue
                runCatching { notifier().notify(ID, build(NowPlayingRepository.room.value, room)) }
            }
        }
    }

    private fun like() {
        val room = FollowSession.following.value ?: return
        if (room.title.isBlank()) return
        val app = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            LikesRepository.get(app).like(room.hostId, room.title, room.artist, room.durationMs)
        }
    }

    private fun notifier(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun startInForeground(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(ID, notification)
            }
            foreground = true
        }
    }

    private fun build(
        track: com.museroom.app.media.NowPlaying?,
        room: Following?,
    ): Notification {
        ensureChannel(this)

        // The room slot is only filled while the joiner is genuinely in step.
        // While a track is still being found the follow state still knows its
        // name, and a notification that empties out between songs reads as a
        // fault rather than as loading.
        val title = track?.title?.ifBlank { null } ?: room?.title.orEmpty()
        val artist = track?.artist?.ifBlank { null } ?: room?.artist.orEmpty()
        val duration = track?.durationMs?.takeIf { it > 0 } ?: room?.durationMs ?: 0L
        val position = room?.positionMs ?: 0L
        val playing = track != null && track.isPlaying
        val art = track?.artwork

        publishSession(title, artist, duration, position, playing, art)

        val who = handle.ifBlank { room?.handle.orEmpty() }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "Listening along" })
            .setContentText(artist.ifBlank { if (who.isBlank()) "" else "with $who" })
            .setSubText(if (who.isBlank()) "Museroom" else "Listening with $who")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open())
            // With icons. A media-style action without one is a shape some
            // system interfaces will not draw and others will not parcel, and
            // the failure lands in whichever process was unlucky.
            .addAction(action(R.drawable.ic_notification, "Like", ACTION_LIKE, 3))
            .addAction(action(R.drawable.ic_notification, "Leave", ACTION_LEAVE, 2))

        if (art != null) builder.setLargeIcon(art)

        session?.sessionToken?.let { token ->
            builder.style = Notification.MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(0, 1)
        }
        return builder.build()
    }

    /**
     * What the system draws the cover and the seek bar from.
     *
     * No actions are declared, which is what stops Android offering play,
     * pause and skip buttons over a player that would ignore all three.
     */
    private fun publishSession(
        title: String,
        artist: String,
        duration: Long,
        position: Long,
        playing: Boolean,
        art: Bitmap?,
    ) {
        val current = session ?: return
        runCatching {
            current.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                    .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
                    .build(),
            )
            current.setPlaybackState(
                PlaybackState.Builder()
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        position,
                        if (playing) 1f else 0f,
                    )
                    .setActions(0)
                    .build(),
            )
        }
    }

    private fun action(icon: Int, title: String, intentAction: String, code: Int): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            title,
            intentFor(intentAction, code),
        ).build()

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * A button on the notification.
     *
     * Started as a foreground service rather than an ordinary one, because
     * that is what it is. An ordinary start from a notification that outlived
     * its process is a start the system refuses, and refusing it is not a
     * quiet no.
     */
    private fun intentFor(action: String, code: Int): PendingIntent {
        val intent = Intent(this, RoomService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, code, intent, flags)
        } else {
            PendingIntent.getService(this, code, intent, flags)
        }
    }

    override fun onDestroy() {
        foreground = false
        runCatching { scope?.cancel() }
        scope = null
        runCatching { session?.release() }
        session = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping Museroom out of recents destroys the window the player lives
        // in, and Chromium will not keep audio without one. Measured, not
        // assumed. So the room ends here rather than leaving a notification
        // sitting over silence.
        FollowSession.stop()
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "listening_room"
        private const val ID = 4202
        private const val EXTRA_HANDLE = "handle"
        const val ACTION_LEAVE = "com.museroom.app.LEAVE_ROOM"
        const val ACTION_LIKE = "com.museroom.app.LIKE_ROOM_TRACK"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Listening room", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "Shown while you are listening along with somebody"
                        setShowBadge(false)
                    },
            )
        }

        fun start(context: Context, handle: String) {
            val intent = Intent(context, RoomService::class.java)
                .putExtra(EXTRA_HANDLE, handle)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RoomService::class.java)) }
        }
    }
}
