package com.museroom.app.sync

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import com.museroom.app.media.Fingerprint
import com.museroom.app.media.NowPlaying
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.media.TrackResolver
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.Realtime
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

    /**
     * An ad is playing at the host's end. Their song is coming back, so the
     * track is kept and the room simply waits in silence.
     */
    data object HostAdvert : FollowState

    /** Loaded, but the player will not start. Carries what it says about itself. */
    data class Silent(val detail: String) : FollowState

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

    /**
     * How long a loaded track is given to make a sound before we say so.
     *
     * A stopped player reads as position zero, and the arithmetic will happily
     * call that "behind by a minute" and keep seeking a track nobody is
     * hearing. Silence has to be its own answer, not a large number.
     */
    private const val SILENT_MS = 12_000L

    private const val TICK_MS = 2_000L

    /** Comfortably inside the two minutes the host counts as still here. */
    private const val PRESENCE_MS = 30_000L

    /**
     * How long a pushed row is preferred over asking again. Longer than a
     * tick, so a healthy socket removes the poll entirely; short enough that
     * a socket which quietly stopped delivering is noticed within seconds.
     */
    private const val PUSH_TRUSTED_MS = 6_000L

    /** Between attempts to get the socket back. */
    private const val RECONNECT_MS = 5_000L

    /**
     * The package a room reports itself under. Deliberately not in the
     * allowlist: this is the one session Museroom constructs rather than
     * reads, so it should never be picked up as though somebody's own copy of
     * Museroom were a music player somebody else could be recorded through.
     */
    private const val ROOM_PACKAGE = "com.museroom.app"

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
        newScope.launch { announcePresence(app, hostId) }
        newScope.launch { listenForChanges(app, hostId) }
    }

    /**
     * The host's row, pushed rather than asked for.
     *
     * This is what closes most of the gap. The loop below still runs and still
     * polls, because a socket is not a promise and silence from one must never
     * be read as "nothing changed" — but when the socket is up, a skip lands
     * here in well under a second instead of waiting out a poll, and the loop
     * finds the new track already waiting for it on its next tick.
     */
    private suspend fun listenForChanges(app: Context, hostId: String) {
        val auth = AuthRepository.get(app)
        while (true) {
            val token = auth.validAccessToken()
            if (token == null) {
                delay(RECONNECT_MS)
                continue
            }
            runCatching {
                Realtime.nowPlayingOf(hostId, token).collect { row ->
                    pushed = row to SystemClock.elapsedRealtime()
                }
            }
            // Either the socket closed or the token expired. Both are worth
            // another go, spaced out enough not to hammer a server that is
            // refusing us for a reason.
            delay(RECONNECT_MS)
        }
    }

    /**
     * The last row the socket pushed, and when.
     *
     * Held rather than acted on directly so that every decision still runs
     * through one loop with one set of rules. A pushed row that is newer than
     * the polled one is simply used in its place.
     */
    @Volatile
    private var pushed: Pair<RemoteNowPlaying, Long>? = null

    /** Whichever account of the host is freshest: the pushed one, or the asked-for one. */
    private suspend fun hostNow(friends: FriendsRepository, hostId: String): RemoteNowPlaying? {
        val recent = pushed?.takeIf {
            SystemClock.elapsedRealtime() - it.second < PUSH_TRUSTED_MS
        }?.first
        // A push is only ever fresher than a poll, never staler: it arrives at
        // the moment of the write. So when there is a recent one, it wins, and
        // the poll is spared entirely.
        if (recent != null) return recent
        return friends.nowPlayingOf(hostId).getOrNull()
    }

    /**
     * Tells the host somebody is in their room, and keeps saying so.
     *
     * On its own loop rather than inside the follow loop, because presence
     * should survive a track that will not load: being there is true even
     * while the music is not.
     */
    private suspend fun announcePresence(app: Context, hostId: String) {
        val sync = SyncEngine.get(app)
        while (true) {
            sync.publishRoomPresence(hostId)
            delay(PRESENCE_MS)
        }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        _following.value = null
        pushed = null
        NowPlayingRepository.setRoomPlayback(null)
        RoomPlayer.leave()
        RoomPlayer.context?.let { context ->
            RoomService.stop(context)
            // Said once on the way out, off the cancelled scope, so the host
            // stops seeing somebody who has left.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                SyncEngine.get(context).publishRoomPresence(null)
            }
        }
    }

    private suspend fun follow(context: Context, hostId: String, handle: String) {
        val friends = FriendsRepository.get(context)
        var lastCorrection = 0L
        var acquireUntil = 0L
        var silentSince = 0L
        var loadedFingerprint = ""
        var loadedId = ""

        while (true) {
            val host = hostNow(friends, hostId)

            // An advert at their end, which is not the same as their having
            // stopped. Caught before the fingerprint is looked at, because an
            // advert row carries no track and would otherwise read as a change
            // of song and throw away a track that is about to come back.
            if (host != null && host.isAdvert) {
                RoomPlayer.pause()
                publish(hostId, handle, FollowState.HostAdvert, host)
                delay(TICK_MS)
                continue
            }

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
                if (id == null) {
                    // Two different problems wear the same face here, so they
                    // are told apart: a page that has not come up yet is not
                    // the same as a song that cannot be found.
                    if (!RoomPlayer.started) {
                        publish(hostId, handle, FollowState.Starting, host)
                        delay(TICK_MS)
                        continue
                    }
                    // Not remembered as loaded. The page may simply not have
                    // been up yet, and a track given up on once would never be
                    // tried again for as long as the host played it.
                    publish(
                        hostId, handle,
                        FollowState.Stuck("Still looking for \"${host.title}\"."), host,
                    )
                    delay(TICK_MS)
                    continue
                }
                loadedFingerprint = fingerprint
                loadedId = id
                publish(hostId, handle, FollowState.Loading(host.title), host)
                RoomPlayer.load(id, hostPosition(host))
                acquireUntil = SystemClock.elapsedRealtime() + ACQUIRE_MS
                silentSince = 0L
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
                silentSince = 0L
                delay(TICK_MS)
                continue
            }

            val now = SystemClock.elapsedRealtime()
            if (now < acquireUntil || snapshot.buffering) {
                publish(hostId, handle, FollowState.CatchingUp, host)
                delay(TICK_MS)
                continue
            }

            // A player that is not playing has no position worth comparing.
            // Reporting an offset here is how "behind by 68s" came to mean
            // "silent", which is the least useful thing it could have meant.
            if (!snapshot.playing) {
                RoomPlayer.play()
                if (silentSince == 0L) silentSince = now
                if (now - silentSince < SILENT_MS) {
                    publish(hostId, handle, FollowState.CatchingUp, host)
                } else {
                    // Say so, and try loading it again. A load that landed
                    // badly recovers from this; one that cannot will keep the
                    // player's own account of itself on screen.
                    publish(hostId, handle, FollowState.Silent(snapshot.detail), host)
                    RoomPlayer.load(loadedId, hostPosition(host))
                    acquireUntil = now + ACQUIRE_MS
                    silentSince = now
                }
                delay(TICK_MS)
                continue
            }
            silentSince = 0L

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

    /**
     * Tells the rest of the app that this counts as listening.
     *
     * Hung off every state change rather than off the one branch that plays,
     * because the states that are not listening are the ones that matter here:
     * an ad, a track still loading, a player that will not start, a host who
     * paused. Leave a room session behind in any of those and the position
     * goes on advancing by arithmetic, quietly crediting silence. Anything
     * other than being in step clears it.
     */
    private fun reportRoomPlayback(state: FollowState, host: RemoteNowPlaying?) {
        val snapshot = RoomPlayer.snapshot.value
        if (state !is FollowState.InStep || host == null || !snapshot.playing) {
            NowPlayingRepository.setRoomPlayback(null)
            return
        }
        NowPlayingRepository.setRoomPlayback(
            NowPlaying(
                packageName = ROOM_PACKAGE,
                sourceLabel = "Museroom room",
                isTracked = true,
                sourceTrackId = snapshot.videoId.ifBlank { null },
                title = host.title,
                artist = host.artist,
                album = "",
                durationMs = if (host.durationMs > 0) host.durationMs else snapshot.durationMs,
                reportedPositionMs = snapshot.positionMs,
                reportedAtElapsed = snapshot.takenAt,
                playbackSpeed = 1f,
                isPlaying = true,
                audioContentType = AudioAttributes.CONTENT_TYPE_MUSIC,
                artwork = null,
                rawMetadata = emptyMap(),
            ),
        )
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
        val previous = _following.value ?: return
        reportRoomPlayback(state, host)
        // An advert says nothing about the song, so the last thing we knew
        // about it stays on screen rather than the card emptying out and
        // filling back in every time one plays.
        val known = host?.takeIf { it.title.isNotBlank() }
        _following.value = Following(
            hostId = hostId,
            handle = handle,
            state = state,
            title = known?.title ?: previous.title,
            artist = known?.artist ?: previous.artist,
            durationMs = known?.durationMs ?: previous.durationMs,
            positionMs = known?.let { hostPosition(it) } ?: previous.positionMs,
        )
    }
}
