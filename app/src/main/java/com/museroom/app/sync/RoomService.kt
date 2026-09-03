package com.museroom.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.museroom.app.MainActivity
import com.museroom.app.R

/**
 * Keeps a room playing when Museroom is not the app on screen.
 *
 * Android will happily stop a process that looks idle, and to the system a
 * WebView playing music looks exactly like a WebView. This service is the
 * declaration that audio is the point, which is also why the notification says
 * who you are listening with rather than something about a background task.
 */
class RoomService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            FollowSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val handle = intent?.getStringExtra(EXTRA_HANDLE).orEmpty()
        val line = intent?.getStringExtra(EXTRA_LINE).orEmpty()
        startInForeground(notification(handle, line))
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun notification(handle: String, line: String): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val leave = PendingIntent.getService(
            this,
            2,
            Intent(this, RoomService::class.java).setAction(ACTION_LEAVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (handle.isBlank()) "Listening along" else "Listening with $handle")
            .setContentText(line.ifBlank { "Museroom is following their music" })
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Leave", leave)
            .build()
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
        private const val EXTRA_LINE = "line"
        const val ACTION_LEAVE = "com.museroom.app.LEAVE_ROOM"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Listening room", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while you are listening along with somebody" },
            )
        }

        fun start(context: Context, handle: String, line: String) {
            val intent = Intent(context, RoomService::class.java)
                .putExtra(EXTRA_HANDLE, handle)
                .putExtra(EXTRA_LINE, line)
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
