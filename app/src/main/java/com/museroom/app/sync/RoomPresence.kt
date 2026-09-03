package com.museroom.app.sync

import android.content.Context
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.RoomMember
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

    private const val POLL_MS = 20_000L

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
                    return@collectLatest
                }
                while (true) {
                    friends.roomMembers().onSuccess { _members.value = it }
                    delay(POLL_MS)
                }
            }
        }
    }
}
