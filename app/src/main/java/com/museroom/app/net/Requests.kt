package com.museroom.app.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything waiting on an answer from you, in one place.
 *
 * There were four of these before, on four schedules: friend requests polled by
 * the Now screen and again by the Friends screen, and room requests polled by
 * the Now screen. Two endpoints, four callers, and no shared answer between
 * them — so nothing could put a number on a button, because there was no
 * number, only several screens each holding their own copy of half of it.
 *
 * The dot is what forced this into one object. A dot is a claim about right
 * now, and a claim assembled from two screens that may not be running is not
 * one anybody should make.
 */
class RequestsRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val auth = AuthRepository.get(app)
    private val friends = FriendsRepository.get(app)
    private val listen = ListenRepository.get(app)

    private val _friendRequests = MutableStateFlow<List<PendingRequest>>(emptyList())

    /** People asking to be your friend. */
    val friendRequests: StateFlow<List<PendingRequest>> = _friendRequests.asStateFlow()

    private val _sentRequests = MutableStateFlow<List<PendingRequest>>(emptyList())

    /** People you asked, still waiting. Nothing to answer, but worth seeing. */
    val sentRequests: StateFlow<List<PendingRequest>> = _sentRequests.asStateFlow()

    private val _listenInbox = MutableStateFlow<List<ListenRequest>>(emptyList())

    /** People asking to listen along with you, minus anything already answered. */
    val listenInbox: StateFlow<List<ListenRequest>> = _listenInbox.asStateFlow()

    private val _count = MutableStateFlow(0)

    /** What the dot reads. Only things that are actually waiting on you. */
    val count: StateFlow<Int> = _count.asStateFlow()

    private var scope: CoroutineScope? = null

    /** Everything the last read returned, before the answered filter. */
    private var rawInbox: List<ListenRequest> = emptyList()

    /**
     * A nudge that something changed, from the socket or from a screen that
     * has just answered something. Conflated, because three of these arriving
     * together are still one reason to re-read.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun start() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        newScope.launch {
            auth.session.collectLatest { session ->
                if (session == null) {
                    rawInbox = emptyList()
                    _friendRequests.value = emptyList()
                    _sentRequests.value = emptyList()
                    _listenInbox.value = emptyList()
                    _count.value = 0
                    return@collectLatest
                }
                val me = session.userId
                launch { listenForChanges(me) }
                while (true) {
                    refresh()
                    // The socket is not a promise, so the slow poll stays
                    // underneath it. Thirty seconds is the worst case for
                    // noticing something, not the usual one.
                    withTimeoutOrNull(POLL_MS) { wake.receive() }
                }
            }
        }

        // Answering from the notification shade never touched the network from
        // here, and the page still has to empty out when it happens.
        newScope.launch {
            AnsweredListenRequests.ids.collectLatest { publishInbox(it) }
        }
    }

    /** Re-read both inboxes. Safe to call from anywhere, including a screen. */
    suspend fun refresh() {
        friends.pending().onSuccess { all ->
            _friendRequests.value = all.filter { it.incoming }
            _sentRequests.value = all.filterNot { it.incoming }
        }
        listen.inbox().onSuccess {
            rawInbox = it
            publishInbox(AnsweredListenRequests.ids.value)
        }
        recount()
    }

    /** Tell the repository something was answered, without waiting for a poll. */
    fun nudge() {
        wake.trySend(Unit)
    }

    private fun publishInbox(answered: Set<Long>) {
        _listenInbox.value = rawInbox.filter { it.id !in answered }
        recount()
    }

    private fun recount() {
        _count.value = _friendRequests.value.size + _listenInbox.value.size
    }

    /**
     * Being told rather than asking.
     *
     * Three subscriptions, because Realtime takes one equality filter each and
     * a friendship is stored as an ordered pair: whether you are user_a or
     * user_b depends on how your id happens to sort against theirs.
     *
     * None of the payloads are used. What arrives is only the news that
     * something moved, and the re-read that follows goes through the ordinary
     * queries, so row-level security and the answered filter stay in one place.
     */
    private suspend fun listenForChanges(me: String) {
        val token = auth.validAccessToken() ?: return
        runCatching {
            Realtime.changes(
                topic = "requests:$me",
                watching = listOf(
                    Watch("listen_requests", "to_user=eq.$me"),
                    // A friendship is stored as an ordered pair, so which
                    // column holds you depends on how your id sorts against
                    // theirs. Both, therefore, and neither is optional.
                    Watch("friendships", "user_a=eq.$me"),
                    Watch("friendships", "user_b=eq.$me"),
                ),
                accessToken = token,
            ).collect { nudge() }
        }
    }

    companion object {
        private const val POLL_MS = 30_000L

        @Volatile private var instance: RequestsRepository? = null

        fun get(context: Context): RequestsRepository =
            instance ?: synchronized(this) {
                instance ?: RequestsRepository(context.applicationContext).also { instance = it }
            }
    }
}
