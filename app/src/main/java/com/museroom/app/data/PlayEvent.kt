package com.museroom.app.data

/**
 * What the phone reports. Never a duration, only a thing that happened and when.
 *
 * Durations are derived from these later, server-side once there is a server,
 * because a client that reports "I listened for 40 minutes" is a client that can
 * be edited to say anything.
 */
enum class PlayEventType {
    /** Playback started or resumed. */
    PLAY,

    /** Playback paused. */
    PAUSE,

    /** The position moved by more than playback alone explains. */
    SEEK,

    /** A different track took over. */
    TRACK_CHANGE,

    /** Still playing, every 30 seconds. */
    HEARTBEAT,

    /** The session went away entirely. */
    STOP,
}

/**
 * Two clocks are carried on purpose. [clockMs] is wall time and survives reboots,
 * which is what a server can reason about. [elapsedMs] is monotonic and is the
 * only safe basis for measuring an interval, because wall time can jump.
 */
data class PlayEvent(
    val id: Long = 0,
    val type: PlayEventType,
    val fingerprint: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sourcePackage: String,
    val positionMs: Long,
    val clockMs: Long,
    val elapsedMs: Long,
    val uploaded: Boolean = false,
)
