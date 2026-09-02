package com.museroom.app.credit

import com.museroom.app.data.ListeningSessionEntity
import com.museroom.app.data.PlayEvent
import com.museroom.app.data.PlayEventType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns a stream of events into minutes we are prepared to defend.
 *
 * This runs on the phone today so that history and today's total work with no
 * network. The same rules will run server-side over the uploaded events, and the
 * server's answer wins: a leaderboard that trusts a number the client computed is
 * a leaderboard that ranks whoever edited their client.
 */
object Crediting {

    /**
     * Longer than this between events and we stop believing the music kept
     * playing. A heartbeat is due every 30 seconds, so two minutes of silence
     * means the process died, the phone slept badly, or something is being faked.
     */
    const val GAP_LIMIT_MS = 120_000L

    /** Sub-second stretches are rounding noise, not listening. */
    const val MIN_CREDIT_MS = 1_000L

    /** Sixteen hours of music in one day is already beyond plausible. */
    const val DAILY_CAP_MS = 16 * 60 * 60 * 1000L

    /**
     * Events must be in the order they happened. Anything the rules cannot
     * account for is dropped rather than credited.
     */
    fun sessions(events: List<PlayEvent>): List<ListeningSessionEntity> {
        if (events.isEmpty()) return emptyList()

        val out = mutableListOf<ListeningSessionEntity>()
        var open: OpenSession? = null

        for (event in events) {
            val current = open
            if (current != null && current.fingerprint != event.fingerprint) {
                // The stretch between the old track's last event and this boundary
                // is listening to the old track, so credit it before closing.
                current.advance(event)
                current.finish()?.let(out::add)
                open = null
            }

            if (open == null) {
                if (event.type == PlayEventType.PAUSE || event.type == PlayEventType.STOP) continue
                open = OpenSession(event)
                continue
            }

            open.advance(event)

            if (event.type == PlayEventType.STOP) {
                open.finish()?.let(out::add)
                open = null
            }
        }

        open?.finish()?.let(out::add)
        return out
    }

    /**
     * Credited milliseconds per calendar day, with the daily cap applied. A
     * session that straddles midnight is attributed to the day it started, which
     * keeps a late-night album from being split across two boards.
     */
    fun dailyTotals(
        sessions: List<ListeningSessionEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<LocalDate, Long> = sessions
        .groupBy { Instant.ofEpochMilli(it.startedAtClock).atZone(zone).toLocalDate() }
        .mapValues { (_, daySessions) ->
            daySessions.sumOf { it.creditedMs }.coerceAtMost(DAILY_CAP_MS)
        }

    private class OpenSession(first: PlayEvent) {
        val fingerprint = first.fingerprint
        private val title = first.title
        private val artist = first.artist
        private val album = first.album
        private val durationMs = first.durationMs
        private val source = first.sourcePackage
        private val startedAtClock = first.clockMs

        private var lastElapsed = first.elapsedMs
        private var lastClock = first.clockMs
        private var playing = first.type.impliesPlaying
        private var accumulated = 0L

        fun advance(event: PlayEvent) {
            if (playing) accumulated += creditableSpan(event)
            lastElapsed = event.elapsedMs
            lastClock = event.clockMs
            when (event.type) {
                PlayEventType.PLAY, PlayEventType.HEARTBEAT, PlayEventType.TRACK_CHANGE -> playing = true
                PlayEventType.PAUSE, PlayEventType.STOP -> playing = false
                PlayEventType.SEEK -> Unit // a seek says where, not whether
            }
        }

        /**
         * The span between two events, believed only as far as both clocks agree.
         * A client that inflates the monotonic clock still cannot inflate wall
         * time past it, and vice versa, so the smaller of the two is the honest
         * number.
         */
        private fun creditableSpan(event: PlayEvent): Long {
            val byElapsed = event.elapsedMs - lastElapsed
            val byClock = event.clockMs - lastClock
            if (byElapsed < 0 || byClock < 0) return 0
            val span = minOf(byElapsed, byClock)
            return if (span > GAP_LIMIT_MS) 0 else span
        }

        fun finish(): ListeningSessionEntity? {
            val wallClockSpan = (lastClock - startedAtClock).coerceAtLeast(0)
            var credited = accumulated.coerceAtMost(wallClockSpan)
            if (durationMs > 0) credited = credited.coerceAtMost(durationMs)
            if (credited < MIN_CREDIT_MS) return null

            return ListeningSessionEntity(
                fingerprint = fingerprint,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                sourcePackage = source,
                startedAtClock = startedAtClock,
                endedAtClock = lastClock,
                creditedMs = credited,
            )
        }
    }
}

private val PlayEventType.impliesPlaying: Boolean
    get() = this == PlayEventType.PLAY ||
        this == PlayEventType.HEARTBEAT ||
        this == PlayEventType.TRACK_CHANGE ||
        this == PlayEventType.SEEK
