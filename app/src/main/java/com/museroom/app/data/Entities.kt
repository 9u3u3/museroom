package com.museroom.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The local outbox. Every event is written here first, so nothing is lost when
 * the phone is offline, and [uploaded] is what a sync pass will later clear.
 */
@Entity(
    tableName = "play_events",
    indices = [Index("uploaded"), Index("clockMs")],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val fingerprint: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sourcePackage: String,
    val positionMs: Long,
    val clockMs: Long,
    val elapsedMs: Long,
    @ColumnInfo(defaultValue = "0") val uploaded: Boolean = false,
)

/**
 * A reconstructed stretch of listening, with the minutes we are prepared to
 * defend. Kept locally so history and today's total work with no network.
 */
@Entity(
    tableName = "listening_sessions",
    indices = [Index("startedAtClock"), Index("fingerprint")],
)
data class ListeningSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sourcePackage: String,
    val startedAtClock: Long,
    val endedAtClock: Long,
    val creditedMs: Long,
    @ColumnInfo(defaultValue = "0") val uploaded: Boolean = false,
)

fun PlayEventEntity.toDomain() = PlayEvent(
    id = id,
    type = PlayEventType.valueOf(type),
    fingerprint = fingerprint,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    sourcePackage = sourcePackage,
    positionMs = positionMs,
    clockMs = clockMs,
    elapsedMs = elapsedMs,
    uploaded = uploaded,
)

fun PlayEvent.toEntity() = PlayEventEntity(
    id = id,
    type = type.name,
    fingerprint = fingerprint,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    sourcePackage = sourcePackage,
    positionMs = positionMs,
    clockMs = clockMs,
    elapsedMs = elapsedMs,
    uploaded = uploaded,
)
