package com.museroom.app.sync

import android.content.Context
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.RoomMember
import com.museroom.app.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Who is listening along with you, kept in one place.
 *
 * This used to live entirely inside the Now screen's own room card, polled
 * only while that card happened to be on screen. The header wants the same
 * number so it can show it the moment the app opens, and two composables
 * polling the same endpoint on their own schedules is just two calls where
 * one would do. Start it once; everyone reads the same answer.
 */
object RoomPresence {

    /**
     * How soon a host learns somebody has joined.
     *
     * It decides more than the roster now. A track change is only held open
     * for people the host knows about, so anybody who arrived since the last
     * look is somebody whose first song starts without them.
     */
    private const val POLL_MS = 8_000L

    private val _members = MutableStateFlow<List<RoomMember>>(emptyList())
    val members: StateFlow<List<RoomMember>> = _members.asStateFlow()

    private var scope: CoroutineScope? = null

    @Synchronized
    fun start(context: Context) {
        if (scope != null) return
        val app = context.applicationContext
        val auth = AuthRepository.get(app)
        val friends = FriendsRepository.get(app)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        newScope.launch {
            auth.session.collectLatest { session ->
                if (session == null) {
                    _members.value = emptyList()
                    seen = emptySet()
                    return@collectLatest
                }
                // First pass after signing in only learns who is already
                // there. Announcing them would mean a notification for
                // everybody still in the room every time the app restarts.
                var first = true
                val me = session.userId
                while (true) {
                    // Through the function rather than by selecting the rows,
                    // because a stranger who walked into your room from Nearby
                    // shares nothing with you and their row is not yours to
                    // read. They are still in your room, and the roster is the
                    // one place that has to know it.
                    friends.roomMembersOf(me).onSuccess { members ->
                        if (!first) announceArrivals(app, members)
                        seen = members.map { it.userId }.toSet()
                        _members.value = members
                        first = false
                    }
                    delay(POLL_MS)
                }
            }
        }
    }

    /** Who was here last time round, so only new faces are worth a message. */
    private var seen: Set<String> = emptySet()

    /**
     * Somebody arriving is the only moment a host would ever learn it, and
     * with an open door there is no request to notice instead. It has to reach
     * them whether or not they are looking at the app, which is why this poll
     * runs from the background service rather than from a screen.
     */
    private fun announceArrivals(app: Context, members: List<RoomMember>) {
        val arrived = members.filter { it.userId !in seen }
        if (arrived.isEmpty()) return
        // One message for the newest arrival rather than a burst of them; the
        // count says how full the room is.
        val newest = arrived.last()
        Notifier.someoneJoined(app, newest.handle, members.size - 1)
    }
}
