package com.museroom.app.sync

import android.content.Context
import android.os.SystemClock
import com.museroom.app.media.Fingerprint
import com.museroom.app.media.TrackResolver
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.RemoteNowPlaying
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
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
)

sealed interface FollowState {
    /** The page is still waking up. */
    data object Starting : FollowState

    /** Working out which recording the host means. */
    data object Finding : FollowState

    data class Loading(val title: String) : FollowState

    /** Loaded, buffering, not yet worth correcting. */
    data object CatchingUp : FollowState

    data class InStep(val offMs: Long) : FollowState

    /** An ad is playing here that the host is not hearing. */
    data object Advert : FollowState

    data object HostQuiet : FollowState

    data class Stuck(val reason: String) : FollowState
}

/**
 * Listening along with somebody, inside Museroom.
 *
 * Nothing about this reaches for another app. The joiner's music comes out of
 * [RoomPlayer], which Museroom owns and can therefore be told exactly what to
 * play and when, so a host changing track changes the joiner's track a moment
 * later with nobody touching anything. That is the whole reason the player is
 * in here rather than being a link to somewhere else.
 *
 * No audio crosses between the phones and none needs to. Each side holds its
 * own copy of the same recording; this only keeps the joiner's copy pointed at
 * the same moment.
 */
object FollowSession {

    /** Past this, the drift is worth the stutter of a seek. */
    private const val TOLERANCE_MS = 2_500L

    /** A seek stutters playback, so corrections are rate limited. */
    private const val MIN_CORRECTION_GAP_MS = 8_000L

    /**
     * How long a freshly loaded track is left alone.
     *
     * Loading is not instant, and correcting a track that is still buffering
     * fights the buffer rather than the drift. So there are two modes: acquire,
     * which waits, and locked, which measures.
     */
    private const val ACQUIRE_MS = 7_000L

    private const val TICK_MS = 2_000L

    /** A YouTube video id, which is the only thing the player can be handed. */
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private val _following = MutableStateFlow<Following?>(null)
    val following: StateFlow<Following?> = _following.asStateFlow()

    private var scope: CoroutineScope? = null

    @Synchronized
    fun start(context: Context, hostId: String, handle: String) {
        stop()
        val app = context.applicationContext
        _following.value = Following(hostId, handle)

        // The page takes seconds to come up, and those seconds are cheaper
        // spent now than after the host's song has already started.
        RoomPlayer.prime(app)
        RoomPlayer.warmUp()
        TrackResolver.searcher = { title, artist -> RoomPlayer.search(title, artist) }
        RoomService.start(app, handle, "Getting in step")

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch { follow(app, hostId, handle) }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        _following.value = null
        RoomPlayer.leave()
        RoomPlayer.context?.let { RoomService.stop(it) }
    }

    private suspend fun follow(context: Context, hostId: String, handle: String) {
        val friends = FriendsRepository.get(context)
        var lastCorrection = 0L
        var acquireUntil = 0L
        var loadedFingerprint = ""
        var loadedId = ""

        while (true) {
            val host = friends.nowPlayingOf(hostId).getOrNull()
            if (host == null || !host.isPlaying || host.title.isBlank()) {
                RoomPlayer.pause()
                publish(hostId, handle, FollowState.HostQuiet, host)
                delay(TICK_MS)
                continue
            }

            val fingerprint = Fingerprint.of(host.title, host.artist, host.durationMs)
            if (fingerprint != loadedFingerprint) {
                publish(hostId, handle, FollowState.Finding, host)
                val id = resolve(context, host)
                loadedFingerprint = fingerprint
                if (id == null) {
                    publish(
                        hostId, handle,
                        FollowState.Stuck("Could not find \"${host.title}\" to play."), host,
                    )
                    delay(TICK_MS)
                    continue
                }
                loadedId = id
                publish(hostId, handle, FollowState.Loading(host.title), host)
                RoomPlayer.load(id, hostPosition(host))
                acquireUntil = SystemClock.elapsedRealtime() + ACQUIRE_MS
                lastCorrection = 0L
                delay(TICK_MS)
                continue
            }

            val snapshot = RoomPlayer.snapshot.value
            if (!snapshot.ready) {
                publish(hostId, handle, FollowState.Starting, host)
                delay(TICK_MS)
                continue
            }

            // An ad is not the host's music, and correcting against it would
            // only seek around inside the ad.
            if (snapshot.ad) {
                publish(hostId, handle, FollowState.Advert, host)
                delay(TICK_MS)
                continue
            }

            // The player finished the track and started something of its own
            // choosing. Identity is the reliable test for that, which is why
            // the id we asked for is kept rather than trusted to stick.
            val wandered = loadedId.isNotBlank() &&
                snapshot.videoId.isNotBlank() &&
                snapshot.videoId != loadedId
            if (wandered) {
                publish(hostId, handle, FollowState.Loading(host.title), host)
                RoomPlayer.load(loadedId, hostPosition(host))
                acquireUntil = SystemClock.elapsedRealtime() + ACQUIRE_MS
                delay(TICK_MS)
                continue
            }

            val now = SystemClock.elapsedRealtime()
            if (now < acquireUntil || snapshot.buffering) {
                publish(hostId, handle, FollowState.CatchingUp, host)
                delay(TICK_MS)
                continue
            }

            if (!snapshot.playing) RoomPlayer.play()

            val off = hostPosition(host) - localPosition(snapshot)
            if (abs(off) > TOLERANCE_MS && now - lastCorrection > MIN_CORRECTION_GAP_MS) {
                RoomPlayer.seekTo(hostPosition(host))
                lastCorrection = now
            }
            publish(hostId, handle, FollowState.InStep(off), host)
            delay(TICK_MS)
        }
    }

    /**
     * Which recording to play.
     *
     * The host's own player sometimes says, in which case there is nothing to
     * work out. Otherwise the catalogue answers, and only a track nobody has
     * ever played costs a search.
     */
    private suspend fun resolve(context: Context, host: RemoteNowPlaying): String? {
        val fromHost = host.sourceTrackId?.substringAfterLast(':')?.trim()
        if (fromHost != null && VIDEO_ID.matches(fromHost)) return fromHost
        return TrackResolver.youtubeId(context, host.title, host.artist, host.durationMs)
    }

    /** Their position now, projected from the snapshot and when it was taken. */
    private fun hostPosition(host: RemoteNowPlaying): Long {
        val takenAt = runCatching { Instant.parse(host.updatedAt).toEpochMilli() }.getOrNull()
            ?: return host.positionMs
        val elapsed = (System.currentTimeMillis() - takenAt).coerceIn(0, 60_000)
        return host.positionMs + elapsed
    }

    /** Ours now, projected the same way, because the reading is a moment old. */
    private fun localPosition(snapshot: RoomPlayer.Snapshot): Long {
        if (!snapshot.playing) return snapshot.positionMs
        val age = (SystemClock.elapsedRealtime() - snapshot.takenAt).coerceIn(0, 10_000)
        return snapshot.positionMs + age
    }

    private fun publish(
        hostId: String,
        handle: String,
        state: FollowState,
        host: RemoteNowPlaying?,
    ) {
        if (_following.value == null) return
        _following.value = Following(
            hostId = hostId,
            handle = handle,
            state = state,
            title = host?.title.orEmpty(),
            artist = host?.artist.orEmpty(),
            durationMs = host?.durationMs ?: 0,
            positionMs = host?.let { hostPosition(it) } ?: 0,
        )
    }
}
