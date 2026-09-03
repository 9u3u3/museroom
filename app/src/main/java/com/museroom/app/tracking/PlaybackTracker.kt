package com.museroom.app.tracking

import android.content.Context
import android.os.SystemClock
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.data.PlayEventType
import com.museroom.app.data.toDomain
import com.museroom.app.data.toEntity
import com.museroom.app.credit.Crediting
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.media.pickActive
import com.museroom.app.privacy.PrivacyState
import com.museroom.app.net.ListenRepository
import com.museroom.app.sync.FollowSession
import com.museroom.app.notify.Notifier
import com.museroom.app.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Watches what is playing and writes the event trail.
 *
 * Deliberately not a foreground service. The notification listener is already a
 * bound service the system keeps alive, which is how scrobblers stay running, and
 * a foreground service would need a declared type that does not honestly describe
 * what this does. If OEM battery management proves too aggressive in practice,
 * the self-check will show it as a stale last event.
 */
object PlaybackTracker {

    private const val PREFS = "museroom.tracking"
    private const val KEY_WATERMARK = "credited_through_event_id"
    private const val KEY_LET_IN_THROUGH = "let_in_through_request_id"
    private const val HEARTBEAT_MS = 30_000L
    private const val NOW_PLAYING_MS = 15_000L

    /** How soon a pause or a skip reaches everybody following. */
    private const val PUBLISH_CHECK_MS = 3_000L
    private const val SYNC_MS = 60_000L
    private const val INBOX_MS = 20_000L

    /** Faster than the inbox, because somebody is waiting on this one. */
    private const val ANSWER_MS = 8_000L

    private val differ = PlaybackDiffer(heartbeatMs = HEARTBEAT_MS)

    /** Ids already announced, so a pending request is not notified every 20s. */
    private val announced = mutableSetOf<Long>()
    private val mutex = Mutex()
    private var scope: CoroutineScope? = null

    fun start(context: Context) {
        if (scope != null) return
        val app = context.applicationContext
        val db = MuseroomDatabase.get(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        privacy = PrivacyState.get(app)
        appContext = app
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        newScope.launch {
            NowPlayingRepository.sessions.collect { record(db, prefs) }
        }

        // Heartbeats are what let the server tell "still listening" from "the app
        // died mid-song". Nothing else drives them; the player has no reason to
        // push an update while a track plays through.
        newScope.launch {
            while (true) {
                delay(HEARTBEAT_MS / 3)
                record(db, prefs)
            }
        }

        // What friends read, and what anybody following is steered by.
        //
        // Pausing has to travel, which is why this no longer skips a paused
        // player. Somebody in your room heard the previous message, "playing",
        // and would go on hearing it for as long as you never sent another.
        val sync = SyncEngine.get(app)
        newScope.launch { publishNowPlaying(sync) }

        // The outbox drains on a slow loop. A failed pass simply retries the same
        // rows next time, because uploads are keyed off a flag, not a clock.
        newScope.launch {
            while (true) {
                delay(SYNC_MS)
                sync.sync()
            }
        }

        // Somebody asking to listen along is the only thing worth interrupting
        // for, and it has to arrive whether or not Museroom is on screen.
        val listen = ListenRepository.get(app)
        newScope.launch {
            while (true) {
                delay(INBOX_MS)
                listen.inbox().onSuccess { requests ->
                    requests.filter { it.id !in announced }.forEach { request ->
                        announced += request.id
                        Notifier.listenRequest(app, request.id, request.handle, request.title)
                    }
                }
            }
        }

        // Being let in starts the music, here, without anybody pressing
        // anything. Asking and then having to find a second button on a screen
        // you are not looking at is the same manual step this was meant to end.
        newScope.launch { watchForBeingLetIn(app, listen, prefs) }
    }

    /**
     * The other half of asking: noticing you were let in.
     *
     * A watermark rather than a clock, and one that starts at whatever you have
     * already sent, so signing in does not replay every room you were ever
     * admitted to.
     */
    private suspend fun watchForBeingLetIn(
        app: Context,
        listen: ListenRepository,
        prefs: android.content.SharedPreferences,
    ) {
        if (!prefs.contains(KEY_LET_IN_THROUGH)) {
            val latest = listen.lastSentId().getOrNull() ?: return
            prefs.edit().putLong(KEY_LET_IN_THROUGH, latest).apply()
        }
        while (true) {
            delay(ANSWER_MS)
            val through = prefs.getLong(KEY_LET_IN_THROUGH, 0L)
            listen.accepted(through).onSuccess { answers ->
                answers.forEach { answer ->
                    prefs.edit().putLong(KEY_LET_IN_THROUGH, answer.id).apply()
                    if (FollowSession.following.value?.hostId == answer.toUser) return@forEach
                    Notifier.letIn(app, answer.handle)
                    FollowSession.start(app, answer.toUser, answer.handle)
                }
            }
        }
    }

    /**
     * Telling everybody else what this phone is doing.
     *
     * Checked often and sent rarely: a change is worth a few seconds' delay at
     * most, and a track playing through unchanged is worth one message every
     * fifteen seconds. Pausing, resuming and skipping all count as changes.
     */
    private suspend fun publishNowPlaying(sync: SyncEngine) {
        var lastState = ""
        var lastSentAt = 0L
        var wasPlaying = false

        while (true) {
            delay(PUBLISH_CHECK_MS)
            val hidden = privacy?.privateSession?.value == true
            val active = NowPlayingRepository.sessions.value.pickActive()
                ?.takeIf { it.isTracked && !hidden }

            val state = "${active?.fingerprint.orEmpty()}|${active?.isPlaying == true}"
            val now = SystemClock.elapsedRealtime()
            val due = now - lastSentAt > NOW_PLAYING_MS
            if (state == lastState && !due) continue

            if (active != null) {
                sync.publishNowPlaying(active, active.positionAt(now))
                wasPlaying = active.isPlaying
            } else if (wasPlaying) {
                // Nothing at all is playing, and the last thing anybody heard
                // from this phone said otherwise.
                sync.publishStopped()
                wasPlaying = false
            }
            lastState = state
            lastSentAt = now
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        differ.reset()
    }

    private var privacy: PrivacyState? = null
    private lateinit var appContext: Context

    private suspend fun record(db: MuseroomDatabase, prefs: android.content.SharedPreferences) {
        mutex.withLock {
            // Private mode is handled here, at the point of capture. Treating it
            // as "nothing is playing" also closes the open session cleanly rather
            // than leaving a track hanging mid-count.
            val hidden = privacy?.privateSession?.value == true
            val active = NowPlayingRepository.sessions.value.pickActive()
                ?.takeIf { it.isTracked && !hidden }
            val events = differ.diff(active, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            if (events.isEmpty()) return@withLock
            events.forEach { db.dao().insertEvent(it.toEntity()) }
            creditClosedSessions(db, prefs)

            // A track change should reach friends now, not up to fifteen seconds
            // later. The periodic publish only exists to keep the position fresh.
            if (events.any { it.type == PlayEventType.TRACK_CHANGE || it.type == PlayEventType.PLAY }) {
                active?.let {
                    SyncEngine.get(appContext).publishNowPlaying(
                        it, it.positionAt(SystemClock.elapsedRealtime()),
                    )
                }
            }
        }
    }

    /**
     * Credits everything up to the most recent point where a track ended. The
     * track still playing is left alone; its minutes are counted when it finishes,
     * so a session is never credited twice.
     */
    private suspend fun creditClosedSessions(
        db: MuseroomDatabase,
        prefs: android.content.SharedPreferences,
    ) {
        val watermark = prefs.getLong(KEY_WATERMARK, 0L)
        val pending = db.dao().eventsAfter(watermark).map { it.toDomain() }
        if (pending.size < 2) return

        val terminal = pending.indexOfLast {
            it.type == PlayEventType.TRACK_CHANGE || it.type == PlayEventType.STOP
        }
        if (terminal <= 0) return

        // The terminal event is included so the final span is credited, then left
        // unconsumed so it can open the next session on the following pass.
        val closed = pending.subList(0, terminal + 1)
        val sessions = Crediting.sessions(closed)
        if (sessions.isNotEmpty()) db.dao().insertSessions(sessions)
        prefs.edit().putLong(KEY_WATERMARK, pending[terminal - 1].id).apply()
    }
}
