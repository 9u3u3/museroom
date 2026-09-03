package com.museroom.app.sync

import android.content.Context
import com.museroom.app.media.Fingerprint
import com.museroom.app.media.PlayOutcome
import com.museroom.app.media.PlayerCommands
import com.museroom.app.media.PlayerPreference
import com.museroom.app.media.Sources
import com.museroom.app.net.FriendsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.abs

data class Following(
    val hostId: String,
    val handle: String,
    val state: FollowState = FollowState.Starting,
)

sealed interface FollowState {
    data object Starting : FollowState
    data class InStep(val offMs: Long) : FollowState
    data class Changing(val title: String) : FollowState
    data object HostQuiet : FollowState
    data class Stuck(val reason: String) : FollowState
}

/**
 * Following somebody else's playback.
 *
 * No audio crosses between phones and none needs to. Each player holds the same
 * track from its own account; all this does is keep the joiner's copy pointed at
 * the same moment, by seeking.
 *
 * Seeking is the reason this works at all. It is supported by every player whose
 * notification has a scrubber, which is all of them, whereas the commands for
 * starting a specific song are not. So the hard part is only ever getting the
 * right track loaded; staying in step after that is arithmetic and a seek.
 */
object FollowSession {

    /** Below this, correcting would be more disruptive than the drift. */
    private const val TOLERANCE_MS = 2_500L

    /** A seek stutters playback, so corrections are rate limited. */
    private const val MIN_CORRECTION_GAP_MS = 8_000L

    private const val TICK_MS = 3_000L

    private val _following = MutableStateFlow<Following?>(null)
    val following: StateFlow<Following?> = _following.asStateFlow()

    private var scope: CoroutineScope? = null

    @Synchronized
    fun start(context: Context, hostId: String, handle: String) {
        stop()
        val app = context.applicationContext
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        _following.value = Following(hostId, handle)

        newScope.launch { follow(app, hostId, handle) }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        _following.value = null
    }

    private suspend fun follow(context: Context, hostId: String, handle: String) {
        val friends = FriendsRepository.get(context)
        var lastCorrection = 0L
        var lastFingerprint = ""

        while (true) {
            val player = preferredPlayer(context)
            if (player == null) {
                update(hostId, handle, FollowState.Stuck("No supported player installed."))
                delay(TICK_MS)
                continue
            }

            val host = friends.nowPlayingOf(hostId).getOrNull()
            if (host == null || !host.isPlaying || host.title.isBlank()) {
                update(hostId, handle, FollowState.HostQuiet)
                delay(TICK_MS)
                continue
            }

            val hostFingerprint = Fingerprint.of(host.title, host.artist, host.durationMs)
            val localTitle = PlayerCommands.localTitle(context, player)
            val localFingerprint = localTitle?.let { Fingerprint.of(it, host.artist, host.durationMs) }

            if (localFingerprint != hostFingerprint) {
                // Different song: load theirs. This is the part that can fail, and
                // it fails loudly rather than pretending to be in step.
                update(hostId, handle, FollowState.Changing(host.title))
                val outcome = PlayerCommands.play(
                    context, player, host.title, host.artist, host.sourceTrackId, host.durationMs,
                )
                if (outcome is PlayOutcome.Failed) {
                    update(hostId, handle, FollowState.Stuck(outcome.reason))
                }
                lastFingerprint = hostFingerprint
                lastCorrection = 0L
                delay(TICK_MS)
                continue
            }

            // Same song. Keep it pointed at the same moment.
            val hostAt = hostPosition(host.positionMs, host.updatedAt)
            val localAt = PlayerCommands.localPosition(context, player)
            if (localAt == null) {
                update(hostId, handle, FollowState.Starting)
                delay(TICK_MS)
                continue
            }

            val off = hostAt - localAt
            val now = System.currentTimeMillis()
            if (abs(off) > TOLERANCE_MS && now - lastCorrection > MIN_CORRECTION_GAP_MS) {
                if (PlayerCommands.seekTo(context, player, hostAt)) {
                    lastCorrection = now
                } else {
                    update(
                        hostId, handle,
                        FollowState.Stuck("${Sources.label(player)} will not take a seek."),
                    )
                    delay(TICK_MS)
                    continue
                }
            }
            update(hostId, handle, FollowState.InStep(off))
            delay(TICK_MS)
        }
    }

    /** Their position now, projected from the snapshot and when it was taken. */
    private fun hostPosition(positionMs: Long, updatedAt: String): Long {
        val takenAt = runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrNull()
            ?: return positionMs
        val elapsed = (System.currentTimeMillis() - takenAt).coerceIn(0, 60_000)
        return positionMs + elapsed
    }

    private fun preferredPlayer(context: Context): String? =
        PlayerPreference.get(context).preferred?.takeIf { PlayerCommands.isInstalled(context, it) }
            ?: Sources.packages.firstOrNull { PlayerCommands.isInstalled(context, it) }

    private fun update(hostId: String, handle: String, state: FollowState) {
        _following.value = Following(hostId, handle, state)
    }
}
