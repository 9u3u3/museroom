package com.museroom.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.museroom.app.MainActivity
import com.museroom.app.R

/**
 * The one thing Museroom interrupts you for: somebody asking to listen along.
 *
 * Deliberately not used for anything else. An app that reads your notifications
 * had better be sparing about posting its own.
 */
object Notifier {

    private const val CHANNEL = "listen_requests"
    private const val ID = 4201

    /** So an answer from the notification can clear the notification. */
    const val REQUEST_ID = ID
    private const val ID_LET_IN = 4203

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Listen requests",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "When someone asks to listen along with you" },
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** You asked to listen along, and they said yes. */
    fun letIn(context: Context, handle: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("@$handle let you in")
            .setContentText("Museroom is playing what they are playing.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 3))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_LET_IN, notification) }
    }

    fun listenRequest(context: Context, requestId: Long, handle: String, title: String) {
        if (!canPost(context)) return
        ensureChannel(context)

        val text = if (title.isBlank()) "wants to listen along" else "wants to listen to $title"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("@$handle $text")
            .setContentText("They hear it as soon as you say yes.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 0))
            // The answer belongs here rather than three taps away, because a
            // person asking to join is waiting while you find the screen.
            .addAction(0, "Let them in", answer(context, requestId, true))
            .addAction(0, "No", answer(context, requestId, false))
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
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
