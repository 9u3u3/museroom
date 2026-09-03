package com.museroom.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.museroom.app.net.AnsweredListenRequests
import com.museroom.app.net.ListenRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Answering from the notification itself.
 *
 * Opening the app is not an answer, and it reads like one: the notification
 * says somebody wants in, you tap it, and nothing has happened. So the answer
 * lives on the notification, where the question was asked.
 */
class ListenActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        if (id == 0L) return
        val accept = intent.action == ACTION_ACCEPT
        val app = context.applicationContext

        // Clear the one notification this answer belongs to, and say so
        // where the in-app card can see it, so the card does not go on asking.
        Notifier.clearRequest(app, id)
        AnsweredListenRequests.mark(id)

        val finish = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ListenRepository.get(app).respond(id, accept)
            } finally {
                finish.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.museroom.app.LISTEN_ACCEPT"
        const val ACTION_DECLINE = "com.museroom.app.LISTEN_DECLINE"
        const val EXTRA_ID = "request_id"
    }
}
