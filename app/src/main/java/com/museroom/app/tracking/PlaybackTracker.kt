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
    private const val HEARTBEAT_MS = 30_000L
    private const val NOW_PLAYING_MS = 15_000L
    private const val SYNC_MS = 60_000L

    private val differ = PlaybackDiffer(heartbeatMs = HEARTBEAT_MS)
    private val mutex = Mutex()
    private var scope: CoroutineScope? = null

    fun start(context: Context) {
        if (scope != null) return
        val app = context.applicationContext
        val db = MuseroomDatabase.get(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

        // What friends read. Overwrites one row rather than appending, and runs on
        // its own cadence so a paused phone is not writing every fifteen seconds.
        val sync = SyncEngine.get(app)
        newScope.launch {
            while (true) {
                delay(NOW_PLAYING_MS)
                val active = NowPlayingRepository.sessions.value.pickActive()
                    ?.takeIf { it.isTracked && it.isPlaying }
                if (active != null) {
                    sync.publishNowPlaying(active, active.positionAt(SystemClock.elapsedRealtime()))
                }
            }
        }

        // The outbox drains on a slow loop. A failed pass simply retries the same
        // rows next time, because uploads are keyed off a flag, not a clock.
        newScope.launch {
            while (true) {
                delay(SYNC_MS)
                sync.sync()
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        differ.reset()
    }

    private suspend fun record(db: MuseroomDatabase, prefs: android.content.SharedPreferences) {
        mutex.withLock {
            val active = NowPlayingRepository.sessions.value.pickActive()?.takeIf { it.isTracked }
            val events = differ.diff(active, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            if (events.isEmpty()) return@withLock
            events.forEach { db.dao().insertEvent(it.toEntity()) }
            creditClosedSessions(db, prefs)
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
