package com.museroom.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stopping the music after a while, for people who fall asleep to it.
 *
 * Two shapes, because people mean two different things. A number of minutes is
 * a promise about the clock and is kept even if that lands in the middle of a
 * song. "End of this track" is a promise about the music, and is checked by
 * [Playback] when a track runs out rather than by a timer, so a long song is
 * allowed to be long.
 *
 * The deadline is stored as a moment rather than as a countdown so that nothing
 * depends on this object being ticked. A phone that slept through most of the
 * hour still wakes up knowing the hour is over.
 */
object SleepTimer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** When the music stops, on the wall clock. Zero when no timer is set. */
    private val _endsAt = MutableStateFlow(0L)
    val endsAt: StateFlow<Long> = _endsAt.asStateFlow()

    /** Whether the current song is the last one. */
    private val _afterThisTrack = MutableStateFlow(false)
    val afterThisTrack: StateFlow<Boolean> = _afterThisTrack.asStateFlow()

    private var pending: Job? = null

    val armed: Boolean get() = _endsAt.value > 0 || _afterThisTrack.value

    /** Milliseconds left, or zero. For a screen that wants to count down. */
    fun remaining(nowMs: Long = System.currentTimeMillis()): Long =
        (_endsAt.value - nowMs).coerceAtLeast(0)

    fun set(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        val at = System.currentTimeMillis() + minutes * 60_000L
        _endsAt.value = at
        pending = scope.launch {
            // Waiting in steps rather than one long sleep, because a phone that
            // dozes stretches a delay and the deadline is what is true.
            while (true) {
                val left = at - System.currentTimeMillis()
                if (left <= 0) break
                delay(left.coerceAtMost(30_000))
            }
            _endsAt.value = 0
            LocalPlayer.pause()
        }
    }

    fun afterTrack() {
        cancel()
        _afterThisTrack.value = true
    }

    fun cancel() {
        pending?.cancel()
        pending = null
        _endsAt.value = 0
        _afterThisTrack.value = false
    }

    /**
     * Asked by [Playback] when a track ends: is this where we stop?
     *
     * Consuming the flag here rather than leaving it set means the timer is
     * spent once it has done its job, so pressing play afterwards does not stop
     * again at the end of the next song.
     */
    fun stopsHere(): Boolean {
        if (!_afterThisTrack.value) return false
        _afterThisTrack.value = false
        return true
    }
}
