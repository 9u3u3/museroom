package com.museroom.app.tracking

import com.museroom.app.data.PlayEvent
import com.museroom.app.data.PlayEventType
import com.museroom.app.media.NowPlaying

/**
 * Decides what happened between two snapshots.
 *
 * Kept free of Android and of any clock of its own, because the interesting cases
 * are all about timing and testing them should not require a device.
 */
class PlaybackDiffer(
    private val heartbeatMs: Long = 30_000L,
    private val seekToleranceMs: Long = 3_000L,
) {

    private var last: NowPlaying? = null
    private var lastHeartbeatElapsed = 0L

    /**
     * @param track what is playing now, or null if nothing tracked is.
     * @param clockMs wall time, which a server can reason about.
     * @param elapsedMs monotonic time, the only safe basis for a duration.
     */
    fun diff(track: NowPlaying?, clockMs: Long, elapsedMs: Long): List<PlayEvent> {
        val previous = last
        val events = mutableListOf<PlayEvent>()

        if (track == null) {
            if (previous != null) {
                events += previous.event(PlayEventType.STOP, clockMs, elapsedMs, previous.positionAt(elapsedMs))
                last = null
            }
            return events
        }

        val position = track.positionAt(elapsedMs)

        when {
            previous == null || previous.fingerprint != track.fingerprint -> {
                if (previous != null) {
                    events += previous.event(
                        PlayEventType.STOP, clockMs, elapsedMs, previous.positionAt(elapsedMs),
                    )
                }
                events += track.event(PlayEventType.TRACK_CHANGE, clockMs, elapsedMs, position)
                lastHeartbeatElapsed = elapsedMs
            }

            !previous.isPlaying && track.isPlaying -> {
                events += track.event(PlayEventType.PLAY, clockMs, elapsedMs, position)
                lastHeartbeatElapsed = elapsedMs
            }

            previous.isPlaying && !track.isPlaying -> {
                events += track.event(PlayEventType.PAUSE, clockMs, elapsedMs, position)
            }

            else -> {
                // The position the old snapshot predicts for right now, against the
                // position the player actually reports. A gap means someone moved it.
                val projected = previous.positionAt(elapsedMs)
                if (kotlin.math.abs(position - projected) > seekToleranceMs) {
                    events += track.event(PlayEventType.SEEK, clockMs, elapsedMs, position)
                    lastHeartbeatElapsed = elapsedMs
                } else if (track.isPlaying && elapsedMs - lastHeartbeatElapsed >= heartbeatMs) {
                    events += track.event(PlayEventType.HEARTBEAT, clockMs, elapsedMs, position)
                    lastHeartbeatElapsed = elapsedMs
                }
            }
        }

        last = track
        return events
    }

    fun reset() {
        last = null
        lastHeartbeatElapsed = 0L
    }
}

private fun NowPlaying.event(
    type: PlayEventType,
    clockMs: Long,
    elapsedMs: Long,
    positionMs: Long,
) = PlayEvent(
    type = type,
    fingerprint = fingerprint,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    sourcePackage = sourceKey.id,
    positionMs = positionMs,
    clockMs = clockMs,
    elapsedMs = elapsedMs,
)
