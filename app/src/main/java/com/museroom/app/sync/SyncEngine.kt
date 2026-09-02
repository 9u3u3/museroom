package com.museroom.app.sync

import android.content.Context
import com.museroom.app.data.ListeningSessionEntity
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.data.ListeningSessionEntity as SessionEntity
import com.museroom.app.data.PlayEventEntity
import com.museroom.app.media.NowPlaying
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.Supabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Moves the local trail to the server.
 *
 * Uploads are keyed off the outbox flags rather than a timestamp, so a failed
 * pass simply retries the same rows next time. Nothing is deleted on success:
 * the events stay on the phone so history works with no network.
 */
class SyncEngine private constructor(context: Context) {

    private val db = MuseroomDatabase.get(context)
    private val auth = AuthRepository.get(context)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    val pendingEvents = db.dao().pendingEventCount()

    /** Pushes everything waiting. Safe to call often; overlapping calls collapse. */
    suspend fun sync(): SyncState = mutex.withLock {
        if (!Supabase.configured) {
            return@withLock SyncState.Failed("No Supabase project configured in this build.")
                .also { _state.value = it }
        }
        val userId = auth.session.value?.userId
        val token = auth.validAccessToken()
        if (token == null || userId.isNullOrBlank()) {
            return@withLock SyncState.SignedOut.also { _state.value = it }
        }

        _state.value = SyncState.Running
        runCatching {
            withContext(Dispatchers.IO) {
                val events = db.dao().pendingEvents()
                if (events.isNotEmpty()) {
                    Supabase.insert("play_events", eventsJson(events, userId), token)
                    db.dao().markEventsUploaded(events.last().id)
                }

                val sessions = db.dao().pendingSessions()
                if (sessions.isNotEmpty()) {
                    Supabase.insert("listening_sessions", sessionsJson(sessions, userId), token)
                    db.dao().markSessionsUploaded(sessions.last().id)
                }
                events.size + sessions.size
            }
        }.fold(
            onSuccess = { SyncState.Synced(it, System.currentTimeMillis()) },
            onFailure = { SyncState.Failed(it.message ?: it::class.java.simpleName) },
        ).also { _state.value = it }
    }

    /**
     * Publishes what is playing right now. Overwrites one row rather than
     * appending, because a friend list only ever wants the latest.
     */
    suspend fun publishNowPlaying(track: NowPlaying?, positionMs: Long) {
        val userId = auth.session.value?.userId ?: return
        val token = auth.validAccessToken() ?: return
        if (track == null) return

        runCatching {
            withContext(Dispatchers.IO) {
                val row = buildJsonArray {
                    add(
                        buildJsonObject {
                            put("user_id", userId)
                            put("fingerprint", track.fingerprint)
                            put("title", track.title)
                            put("artist", track.artist)
                            put("album", track.album)
                            put("duration_ms", track.durationMs)
                            put("position_ms", positionMs)
                            put("is_playing", track.isPlaying)
                            put("source_package", track.packageName)
                            put("source_track_id", track.sourceTrackId)
                            put("updated_at", Instant.now().toString())
                        },
                    )
                }
                Supabase.insert("now_playing", row, token, upsertOnConflict = "user_id")
            }
        }
    }

    /**
     * Removes one entry everywhere: the local row, the server row, and the events
     * behind it. Deleting only locally would leave the minutes standing on the
     * leaderboard, which is the opposite of what someone asking to remove
     * something wants.
     */
    suspend fun deleteEverywhere(session: SessionEntity) {
        withContext(Dispatchers.IO) {
            db.dao().deleteSession(session.id)
            db.dao().deleteEventsFor(session.fingerprint)

            val userId = auth.session.value?.userId ?: return@withContext
            val token = auth.validAccessToken() ?: return@withContext
            val scope = "user_id=eq.$userId&fingerprint=eq.${session.fingerprint.encoded()}"
            runCatching {
                Supabase.delete(
                    "listening_sessions",
                    "$scope&started_at=eq.${Instant.ofEpochMilli(session.startedAtClock)}",
                    token,
                )
                Supabase.delete("play_events", scope, token)
            }
        }
    }

    /** Wipes everything, here and on the server. */
    suspend fun deleteAllHistory() {
        withContext(Dispatchers.IO) {
            db.dao().clearSessions()
            db.dao().clearEvents()

            val userId = auth.session.value?.userId ?: return@withContext
            val token = auth.validAccessToken() ?: return@withContext
            runCatching {
                Supabase.delete("listening_sessions", "user_id=eq.$userId", token)
                Supabase.delete("play_events", "user_id=eq.$userId", token)
                Supabase.delete("now_playing", "user_id=eq.$userId", token)
            }
        }
    }

    /** PostgREST filters are URL values; a fingerprint can contain anything. */
    private fun String.encoded(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    private fun eventsJson(events: List<PlayEventEntity>, userId: String): JsonArray = buildJsonArray {
        events.forEach { e ->
            add(
                buildJsonObject {
                    put("user_id", userId)
                    put("type", e.type)
                    put("fingerprint", e.fingerprint)
                    put("title", e.title)
                    put("artist", e.artist)
                    put("album", e.album)
                    put("duration_ms", e.durationMs)
                    put("source_package", e.sourcePackage)
                    put("position_ms", e.positionMs)
                    put("client_clock_ms", e.clockMs)
                    put("client_elapsed_ms", e.elapsedMs)
                },
            )
        }
    }

    private fun sessionsJson(sessions: List<ListeningSessionEntity>, userId: String): JsonArray = buildJsonArray {
        sessions.forEach { s ->
            add(
                buildJsonObject {
                    put("user_id", userId)
                    put("fingerprint", s.fingerprint)
                    put("title", s.title)
                    put("artist", s.artist)
                    put("album", s.album)
                    put("duration_ms", s.durationMs)
                    put("source_package", s.sourcePackage)
                    put("started_at", Instant.ofEpochMilli(s.startedAtClock).toString())
                    put("ended_at", Instant.ofEpochMilli(s.endedAtClock).toString())
                    put("credited_ms", s.creditedMs)
                },
            )
        }
    }

    companion object {
        @Volatile private var instance: SyncEngine? = null

        fun get(context: Context): SyncEngine =
            instance ?: synchronized(this) {
                instance ?: SyncEngine(context.applicationContext).also { instance = it }
            }
    }
}

sealed interface SyncState {
    data object Idle : SyncState
    data object SignedOut : SyncState
    data object Running : SyncState
    data class Synced(val rows: Int, val atMs: Long) : SyncState
    data class Failed(val reason: String) : SyncState
}
