package com.museroom.app.sync

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import com.museroom.app.media.Artwork
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

    /**
     * How often to look while a track is still starting.
     *
     * A load takes seconds and the moment it starts playing is the moment
     * worth catching, so the loop watches closely for it and goes back to
     * strolling once the track is under way.
     */
    private const val SETTLING_TICK_MS = 350L

    /** Below this, a seek would cost more in stutter than it buys in accuracy. */
    private const val FIRST_SYNC_TOLERANCE_MS = 700L

    /** Long enough for one seek to land before anything measures the result. */
    private const val SETTLE_MS = 2_500L

    /** Comfortably inside the two minutes the host counts as still here. */
    private const val PRESENCE_MS = 30_000L

    /**
     * How long a pushed row is preferred over asking again. Longer than a
     * tick, so a healthy socket removes the poll entirely; short enough that
     * a socket which quietly stopped delivering is noticed within seconds.
     */
    private const val PUSH_TRUSTED_MS = 6_000L

    /**
     * How long a polled row stands in for asking again.
     *
     * A cap on requests rather than on staleness: the loop's rate changes with
     * what it is doing, and this keeps the traffic it makes from changing with
     * it. Comfortably under the ordinary tick, so the usual cadence is
     * untouched.
     */
    private const val POLL_TRUSTED_MS = 1_500L

    /** Between attempts to get the socket back. */
    private const val RECONNECT_MS = 5_000L

    /**
     * How close to the end of a track counts as over.
     *
     * Inside this, reloading is pointless: the copy would end again before
     * anything could be done with it, and every ending hands the page back its
     * own queue.
     */
    private const val END_OF_TRACK_MS = 6_000L

    /**
     * The package a room reports itself under. Deliberately not in the
     * allowlist: this is the one session Museroom constructs rather than
     * reads, so it should never be picked up as though somebody's own copy of
     * Museroom were a music player somebody else could be recorded through.
     */
    private const val ROOM_PACKAGE = "com.museroom.app"

    /** A YouTube video id, which is the only thing the player can be handed. */
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /**
     * The cover for whatever the room is playing.
     *
     * A joiner's track comes out of Museroom rather than out of a music app,
     * so there is no artwork in a media session for anyone to read, and the
     * big card at the top of their screen was a flat purple square while the
     * strip underneath — which looks the picture up by name — had it. Looked
     * up once per track and carried on the session itself, so everything that
     * draws the room draws the same picture.
     */
    @Volatile private var cover: android.graphics.Bitmap? = null

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
        RoomService.start(app, handle)

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
                    // Newer than anything the poll is holding, by definition.
                    polled = null
                    obeyImmediately(row)
                }
            }
            // Either the socket closed or the token expired. Both are worth
            // another go, spaced out enough not to hammer a server that is
            // refusing us for a reason.
            delay(RECONNECT_MS)
        }
    }

    /**
     * Stopping, without waiting to be asked twice.
     *
     * Everything else can wait for the loop's next look, and should, because
     * one place deciding with one set of rules is what keeps this honest. A
     * pause cannot. The push arrives under a second after the host presses
     * it, and then the joiner used to play on until the loop next came round
     * — so a host pausing was heard as the music carrying on for a couple of
     * seconds and stopping for no visible reason.
     *
     * Only the two states that are unambiguous from the row alone: they
     * stopped, or an advert started. Starting again is left to the loop,
     * which knows whether the right track is even loaded.
     */
    private fun obeyImmediately(row: RemoteNowPlaying) {
        if (saysStop(row)) RoomPlayer.pause()
    }

    /**
     * Whether a row, on its own, is reason enough to go quiet at once.
     *
     * Deliberately narrow. Only what is unambiguous from the row without
     * knowing anything about our own player: they stopped, an advert started,
     * or there is no track in it at all. Anything needing both sides of the
     * story stays with the loop.
     */
    internal fun saysStop(row: RemoteNowPlaying): Boolean =
        !row.isPlaying || row.isAdvert || row.title.isBlank()

    /**
     * The last row the socket pushed, and when.
     *
     * Held rather than acted on directly so that every decision still runs
     * through one loop with one set of rules. A pushed row that is newer than
     * the polled one is simply used in its place.
     */
    @Volatile
    private var pushed: Pair<RemoteNowPlaying, Long>? = null

    /**
     * The last answer the poll gave, and when it gave it.
     *
     * The loop looks far more often while a track is starting than it used to,
     * and without this every one of those looks would be a request. Caching is
     * safe because a row carries the moment it was written: a reading two
     * seconds old still projects to the right position now, so age costs
     * nothing that matters here.
     */
    @Volatile
    private var polled: Pair<RemoteNowPlaying?, Long>? = null

    /** Whichever account of the host is freshest: the pushed one, or the asked-for one. */
    private suspend fun hostNow(friends: FriendsRepository, hostId: String): RemoteNowPlaying? {
        val now = SystemClock.elapsedRealtime()
        // A push is only ever fresher than a poll, never staler: it arrives at
        // the moment of the write. So when there is a recent one, it wins, and
        // the poll is spared entirely.
        pushed?.takeIf { now - it.second < PUSH_TRUSTED_MS }?.let { return it.first }
        polled?.takeIf { now - it.second < POLL_TRUSTED_MS }?.let { return it.first }
        val fresh = friends.nowPlayingOf(hostId).getOrNull()
        polled = fresh to SystemClock.elapsedRealtime()
        return fresh
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
        polled = null
        cover = null
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

        /**
         * A track was just handed to the player and has not been checked
         * against the host since it started making a sound.
         *
         * This is where the missing opening went. A load is given the host's
         * position at the moment it is issued, but the page then spends
         * seconds fetching and buffering, and by the time sound comes out the
         * host has moved on. So the joiner starts life behind by however long
         * the load took, and the ordinary tolerance turns that into a jump
         * several seconds later — the opening either heard twice or skipped.
         */
        var pendingFirstSync = false

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
                    // Quiet while we look. Whatever the page was playing is
                    // not what the host is playing, and letting it run is how
                    // somebody ends up hearing a song nobody chose.
                    RoomPlayer.pause()
                    publish(
                        hostId, handle,
                        FollowState.Stuck("Still looking for \"${host.title}\"."), host,
                    )
                    delay(TICK_MS)
                    continue
                }
                loadedFingerprint = fingerprint
                loadedId = id
                cover = Artwork.cached(host.title, host.artist)
                publish(hostId, handle, FollowState.Loading(host.title), host)
                RoomPlayer.load(id, hostPosition(host))
                // After the load rather than before it: the music matters more
                // than the picture, and this can take a moment.
                if (cover == null) cover = Artwork.fetch(host.title, host.artist)
                acquireUntil = SystemClock.elapsedRealtime() + ACQUIRE_MS
                silentSince = 0L
                lastCorrection = 0L
                pendingFirstSync = true
                delay(SETTLING_TICK_MS)
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
            // the id we asked for is kept rather than trusted to stick. The
            // page silences itself the moment this happens; this decides what
            // to do about it.
            val wandered = loadedId.isNotBlank() &&
                snapshot.videoId.isNotBlank() &&
                snapshot.videoId != loadedId
            if (wandered) {
                if (!worthReloading(host.durationMs, hostPosition(host))) {
                    // Their song is nearly over and ours has already ended.
                    // Reloading the last few seconds only ends again, and the
                    // page starts something of its own each time round — which
                    // is how a listener ended up on a different Drake song from
                    // the host. Wait for whatever they play next instead.
                    RoomPlayer.pause()
                    publish(hostId, handle, FollowState.CatchingUp, host)
                } else {
                    publish(hostId, handle, FollowState.Loading(host.title), host)
                    RoomPlayer.load(loadedId, hostPosition(host))
                    acquireUntil = SystemClock.elapsedRealtime() + ACQUIRE_MS
                    silentSince = 0L
                }
                delay(TICK_MS)
                continue
            }

            val now = SystemClock.elapsedRealtime()
            if (now < acquireUntil || snapshot.buffering) {
                // One correction that does not wait for the settle window.
                // The moment a freshly loaded track is genuinely playing, it
                // is put where the host is now rather than where the host was
                // when the load was issued. Everything after this can afford
                // to wait; this cannot, because waiting is what costs the
                // opening of the song.
                if (pendingFirstSync && snapshot.playing && !snapshot.buffering &&
                    snapshot.onWantedTrack && !snapshot.strayed
                ) {
                    val out = hostPosition(host) - localPosition(snapshot)
                    if (abs(out) > FIRST_SYNC_TOLERANCE_MS) RoomPlayer.seekTo(hostPosition(host))
                    pendingFirstSync = false
                    lastCorrection = now
                    acquireUntil = now + SETTLE_MS
                }
                publish(hostId, handle, FollowState.CatchingUp, host)
                // Looked at often while a track is still finding its feet, and
                // rarely once it has. The whole cost of the missing opening was
                // spent waiting for the next ordinary tick.
                delay(if (pendingFirstSync) SETTLING_TICK_MS else TICK_MS)
                continue
            }
            pendingFirstSync = false

            // A player that is not playing has no position worth comparing.
            // Reporting an offset here is how "behind by 68s" came to mean
            // "silent", which is the least useful thing it could have meant.
            if (!snapshot.playing) {
                // Never resume a player that has strayed. It was stopped
                // because it had started somebody else's song, and pressing
                // play on it plays that song.
                if (!snapshot.strayed) RoomPlayer.play()
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
                artwork = cover,
                rawMetadata = emptyMap(),
            ),
        )
    }

    /**
     * Whether a strayed player is worth pulling back to the track it left.
     *
     * Near the end it is not: the copy would end again within seconds, and
     * every ending hands the page back its own queue to start something of
     * its own. Waiting for the host's next track is quieter and quicker.
     *
     * A track of unknown length is always worth reloading, because there is
     * no end for it to be near.
     */
    internal fun worthReloading(hostDurationMs: Long, hostPositionMs: Long): Boolean =
        hostDurationMs <= 0 || hostDurationMs - hostPositionMs >= END_OF_TRACK_MS

    /**
     * Their position now, projected from the snapshot and when it was taken.
     *
     * Only while they are actually playing. A paused player is where it was
     * left, and projecting one forward is how a host pausing at 0:22 showed
     * up on a joiner's phone as a clock climbing to 0:35, snapping back on the
     * next heartbeat, and climbing again — a sawtooth over a track that had
     * stopped.
     */
    internal fun hostPosition(host: RemoteNowPlaying): Long {
        if (!host.isPlaying) return host.positionMs
        val takenAt = runCatching { Instant.parse(host.updatedAt).toEpochMilli() }.getOrNull()
            ?: return host.positionMs
        val elapsed = (System.currentTimeMillis() - takenAt).coerceIn(0, 60_000)
        val projected = host.positionMs + elapsed
        // A row that went stale near the end would otherwise project past the
        // end of the track and sit there, still counting.
        return if (host.durationMs > 0) projected.coerceAtMost(host.durationMs) else projected
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
            positionMs = shownPosition(state, known) ?: previous.positionMs,
        )
    }

    /**
     * The number on the joiner's screen.
     *
     * Their own player, whenever it is playing, rather than arithmetic on the
     * host's last message. The player is the thing they are actually hearing
     * and the loop already holds it in step, so reading it is both smoother
     * and truer: the host's projection jumped forward between heartbeats and
     * snapped back on each one, which is the jitter people were seeing.
     *
     * Anything else falls back to where the host says they are, unprojected.
     */
    private fun shownPosition(state: FollowState, host: RemoteNowPlaying?): Long? {
        val snapshot = RoomPlayer.snapshot.value
        val ours = state is FollowState.InStep && snapshot.playing && !snapshot.ad
        if (ours) return localPosition(snapshot)
        return host?.let { hostPosition(it) }
    }
}
