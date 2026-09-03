package com.museroom.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.museroom.app.MainActivity
import com.museroom.app.R

/**
 * What Museroom will interrupt you for, and how loudly.
 *
 * Three channels, because these are three different sizes of news and Android
 * only lets a person turn off what it can tell apart. Somebody waiting on an
 * answer rings; somebody arriving in your room arrives quietly; a friend
 * putting a record on is ambient and never makes a sound. An app that reads
 * your notifications had better be careful about posting its own.
 */
object Notifier {

    /**
     * Version two of the asking channel. An existing channel's importance
     * cannot be raised after the fact, so a channel that should ring and never
     * did has to be a new one; the old id is deleted so it stops sitting in
     * the settings list saying nothing.
     */
    private const val CHANNEL_ASK = "listen_requests_v2"
    private const val CHANNEL_ASK_OLD = "listen_requests"
    private const val CHANNEL_ROOM = "room_activity"
    private const val CHANNEL_FRIENDS = "friend_activity"

    private const val ID_LET_IN = 4203
    private const val ID_JOINED = 4204
    private const val ID_FRIEND_BASE = 4400
    private const val ID_REQUEST_BASE = 4600
    private const val ID_LIKE_BASE = 4800

    /** The notification for one particular request, so answering clears it. */
    fun requestNotificationId(requestId: Long): Int =
        ID_REQUEST_BASE + (requestId % 200).toInt()

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.deleteNotificationChannel(CHANNEL_ASK_OLD) }

        if (manager.getNotificationChannel(CHANNEL_ASK) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ASK,
                    "Requests to listen along",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "When someone asks to join your room"
                    // Somebody is standing there waiting for an answer, so this
                    // one uses whatever notification sound the phone is set to
                    // rather than arriving in silence.
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ROOM) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ROOM,
                    "Your room",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "When somebody joins or lets you into a room" },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_FRIENDS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_FRIENDS,
                    "Friends listening",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "When a friend starts playing something"
                    // Silent on purpose. A friend putting a record on is worth
                    // knowing and never worth a noise.
                    setSound(null, null)
                },
            )
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Somebody is asking to join your room, and is waiting on the answer. */
    fun listenRequest(context: Context, requestId: Long, handle: String, title: String) {
        if (!canPost(context)) return
        ensureChannel(context)

        val text = if (title.isBlank()) "wants to listen along" else "wants to listen to $title"
        val notification = NotificationCompat.Builder(context, CHANNEL_ASK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$handle $text")
            .setContentText("They hear it as soon as you say yes.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 0))
            // The answer belongs here rather than three taps away, because a
            // person asking to join is waiting while you find the screen.
            .addAction(0, "Let them in", answer(context, requestId, true))
            .addAction(0, "No", answer(context, requestId, false))
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(requestNotificationId(requestId), notification)
        }
    }

    /**
     * Answered elsewhere. Called when the in-app card is used, so the shade
     * does not go on asking a question that already has an answer.
     */
    fun clearRequest(context: Context, requestId: Long) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(requestNotificationId(requestId))
        }
    }

    /** You asked to listen along, and they said yes. */
    fun letIn(context: Context, handle: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ROOM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$handle let you in")
            .setContentText("Museroom is playing what they are playing.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 3))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_LET_IN, notification) }
    }

    /**
     * Somebody is in your room.
     *
     * Worth its own message because with an open door there was no asking, so
     * this is the only moment anybody would ever learn it happened.
     */
    fun someoneJoined(context: Context, handle: String, others: Int) {
        if (!canPost(context)) return
        ensureChannel(context)
        val text = when {
            others <= 0 -> "They are listening along with you."
            others == 1 -> "They and one other are listening along."
            else -> "They and $others others are listening along."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ROOM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$handle joined your room")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 4))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_JOINED, notification) }
    }

    /** A friend has put something on. Silent, and grouped under one channel. */
    fun friendListening(context: Context, userId: String, handle: String, track: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_FRIENDS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$handle is listening")
            .setContentText(track.ifBlank { "Something is playing." })
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 5))
            .build()
        val id = ID_FRIEND_BASE + (userId.hashCode().mod(120))
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /**
     * Somebody liked what you were playing.
     *
     * Quiet, like the other social messages. Getting one is the whole point of
     * a like, and a number that only moves while you happen to be looking at
     * your own page is not worth collecting.
     */
    fun liked(context: Context, handle: String, track: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_FRIENDS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$handle liked your music")
            .setContentText(track.ifBlank { "Something you were playing." })
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 6))
            .build()
        val id = ID_LIKE_BASE + (handle.hashCode().mod(60))
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun answer(context: Context, requestId: Long, accept: Boolean): PendingIntent {
        val intent = Intent(context, ListenActions::class.java)
            .setAction(if (accept) ListenActions.ACTION_ACCEPT else ListenActions.ACTION_DECLINE)
            .putExtra(ListenActions.EXTRA_ID, requestId)
        return PendingIntent.getBroadcast(
            context,
            (requestId * 2 + if (accept) 1 else 0).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
