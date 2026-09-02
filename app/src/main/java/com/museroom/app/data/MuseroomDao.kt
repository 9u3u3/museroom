package com.museroom.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MuseroomDao {

    @Insert
    suspend fun insertEvent(event: PlayEventEntity): Long

    @Query("SELECT * FROM play_events ORDER BY id ASC")
    suspend fun allEvents(): List<PlayEventEntity>

    /** The outbox, for the sync pass that does not exist yet. */
    @Query("SELECT * FROM play_events WHERE uploaded = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun pendingEvents(limit: Int = 500): List<PlayEventEntity>

    @Query("SELECT * FROM play_events WHERE id > :afterId ORDER BY id ASC")
    suspend fun eventsAfter(afterId: Long): List<PlayEventEntity>

    @Query("DELETE FROM play_events WHERE id <= :throughId")
    suspend fun deleteEventsThrough(throughId: Long)

    @Insert
    suspend fun insertSessions(sessions: List<ListeningSessionEntity>)

    @Query("SELECT * FROM listening_sessions ORDER BY startedAtClock DESC LIMIT :limit")
    fun recentSessions(limit: Int = 100): Flow<List<ListeningSessionEntity>>

    @Query("SELECT COALESCE(SUM(creditedMs), 0) FROM listening_sessions WHERE startedAtClock >= :sinceClock")
    fun creditedSince(sinceClock: Long): Flow<Long>

    @Query(
        """
        SELECT artist AS artist, SUM(creditedMs) AS creditedMs
        FROM listening_sessions
        WHERE startedAtClock >= :sinceClock AND artist != ''
        GROUP BY artist
        ORDER BY creditedMs DESC
        LIMIT :limit
        """,
    )
    fun topArtistsSince(sinceClock: Long, limit: Int = 10): Flow<List<ArtistTotal>>

    @Query("SELECT MAX(id) FROM play_events")
    suspend fun lastEventId(): Long?

    @Query("SELECT * FROM listening_sessions WHERE fingerprint = :fingerprint")
    suspend fun sessionsFor(fingerprint: String): List<ListeningSessionEntity>

    /** Backs the delete-my-history control, and gives tests a clean slate. */
    @Query("UPDATE play_events SET uploaded = 1 WHERE id <= :throughId")
    suspend fun markEventsUploaded(throughId: Long)

    @Query("SELECT * FROM listening_sessions WHERE uploaded = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun pendingSessions(limit: Int = 200): List<ListeningSessionEntity>

    @Query("UPDATE listening_sessions SET uploaded = 1 WHERE id <= :throughId")
    suspend fun markSessionsUploaded(throughId: Long)

    @Query("SELECT COUNT(*) FROM play_events WHERE uploaded = 0")
    fun pendingEventCount(): Flow<Int>

    @Query("DELETE FROM play_events")
    suspend fun clearEvents()

    @Query("DELETE FROM listening_sessions")
    suspend fun clearSessions()
}

data class ArtistTotal(val artist: String, val creditedMs: Long)
