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
import com.museroom.app.net.ServerClock
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

    /**
     * Past this, the drift is worth the stutter of a seek.
     *
     * It was two and a half seconds, which was not a judgement about what
     * people can hear — it was the width of the disagreement between two
     * phones' clocks, absorbed rather than fixed. Now that both ends work in
     * the database's time, this can be what it was always meant to be: the
     * point where walking a gap off would take longer than the jump is worth.
     *
     * Five per cent of speed buys fifty milliseconds a second, so anything
     * approaching half a second would take ten seconds to walk off. Past here
     * the jump is quicker and, being rare, quieter overall.
     */
    private const val TOLERANCE_MS = 400L

    /** Close enough that chasing it would only mean never settling. */
    private const val IN_STEP_MS = 120L

    /**
     * How long a nudge is aimed to take to close the gap it was given.
     *
     * Proportional, so a gap of a fifth of a second is chased more gently than
     * one of a third, and everything larger than the band leans on the clamp.
     */
    private const val CLOSE_OVER_MS = 5_000.0

    /** Five per cent. Past this it stops being something nobody notices. */
    private const val MAX_NUDGE = 0.05

    /** Not worth crossing to the page to change the speed by less than this. */
    private const val RATE_STEP = 0.004

    /** A seek stutters playback, so corrections are rate limited. */
    private const val MIN_CORRECTION_GAP_MS = 6_000L

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

    /**
     * How long after the agreed moment a schedule is still worth acting on.
     *
     * Past this it belongs to a track that has been playing for a while, and
     * acting on it would restart a song somebody is halfway through.
     */
    private const val SCHEDULE_FORGOTTEN_MS = 30_000L

    /**
     * How late is still worth turning up for.
     *
     * Past this the moment has gone and the song is running without us. The
     * important part is what must NOT happen then: a schedule says where the
     * track begins, so acting on a stale one starts a song from the top while
     * everybody else is a minute into it. Somebody joining a room mid-track
     * would be dragged back to the beginning and then hauled forward again.
     */
    /**
     * How far behind the host a room deliberately runs.
     *
     * Everything that ever went wrong at the start of a song came from the
     * same place: a listener is told a track exists at the instant it begins,
     * and cannot have fetched it yet. Trying to be level with the host meant
     * either starting late and skipping the difference, or being hauled
     * forward once the difference was noticed. Both cost somebody the opening
     * of a song.
     *
     * So the room does not try to be level. It runs a set distance behind, and
     * that distance is the budget for finding and fetching the next track. It
     * is spent before the song starts rather than during it, which is the
     * whole difference: nothing has to be skipped, because nothing is late.
     *
     * Three seconds is generous enough to cover an ordinary lookup and load,
     * and small enough that a friend saying "this bit" still lands.
     */
    private const val ROOM_LAG_MS = 3_000L

    /**
     * How long everybody is given to fetch a song before the room starts it.
     *
     * Listeners used to begin a new track the moment their own download
     * finished, so a quick phone and a slow one started a second or two apart
     * and only drew level again over the following half-minute. They begin on
     * a moment now, and the moment has to be one every phone can meet, so it
     * sits this far past the point where the previous track's tail runs out.
     *
     * A phone that misses it anyway starts late rather than skipping, and
     * closes the gap by speed exactly as before.
     */
    private const val FETCH_ALLOWANCE_MS = 2_500L

    /**
     * The longest the last song is allowed to hold up the next one.
     *
     * A tail that never ends is a player that has stopped saying anything, and
     * waiting on it forever would mean the room simply never moves on.
     */
    private const val LONGEST_TAIL_MS = 20_000L

    /**
     * The longest a track may be left settling before it is corrected anyway.
     *
     * The settling window used to end either when its clock ran out or when
     * the page stopped saying it was buffering, and a page that says it is
     * buffering forever meant a listener stuck on "catching up" for as long as
     * they cared to watch. A window that can be held open by the thing it is
     * waiting for is not a window.
     */
    private const val LONGEST_ACQUIRE_MS = 12_000L

    /**
     * How long after a jump its result is worth measuring.
     *
     * A seek is not instant, and a reading taken while one is in flight
     * describes where the player was, not where it was sent. Acting on that
     * produces a second correction for a gap that has already been closed.
     */
    private const val SEEK_SETTLE_MS = 1_200L

    /** How many times a track is re-handed to a page that keeps refusing it. */
    private const val RELOAD_ATTEMPTS = 3

    /**
     * How far a single reading is allowed to be carried forward.
     *
     * The host writes their position on every change and again every fifteen
     * seconds, so a row a few seconds old is ordinary and worth projecting. A
     * row a minute old is a phone that stopped saying anything, and running
     * the arithmetic over that gap invents a position nobody is at. Starting a
     * track there is a song beginning from the middle, and the correction that
     * follows is it jumping back — which is exactly what it looked like.
     */
    private const val LONGEST_PROJECTION_MS = 20_000L

    /**
     * Past this, a row is too old to start a track from at all.
     *
     * Being a moment behind is worth fixing later; beginning in the wrong
     * place is worth waiting a couple of seconds to avoid.
     */
    private const val TOO_OLD_TO_START_MS = 25_000L

    /**
     * How long the host is allowed to still look stopped after the room began.
     *
     * We stopped them, so their row says stopped, and it keeps saying so until
     * their phone notices being started again and writes it down. In that gap
     * the truthful reading of the row is the wrong one, and acting on it would
     * pause a room in the first second of a song.
     */
    private const val RESUME_GRACE_MS = 8_000L

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
        // Before anything is compared against anything. A room is two phones
        // agreeing about a moment, and they cannot until they agree about the
        // time.
        newScope.launch { ServerClock.sync() }
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
        if (saysStop(row)) {
            RoomPlayer.pause()
            return
        }
        // Starting again, on the same track we already have loaded.
        //
        // Left to the loop this was up to two seconds late, and those two
        // seconds do not go away afterwards — the joiner is simply that far
        // behind for the rest of the song, and closing a gap that size by
        // speed alone takes the best part of a minute.
        //
        // Seeking is free here, which is the whole reason this is worth doing
        // at all: the music is already stopped, so there is no jump to hear.
        // Only ever on the track we were told to play, so a host who skipped
        // while paused is left to the loop, where the new song is resolved.
        val snapshot = RoomPlayer.snapshot.value
        val sameTrack = loaded.isNotBlank() &&
            loaded == Fingerprint.of(row.title, row.artist, row.durationMs)
        if (!sameTrack || snapshot.playing || snapshot.strayed || !snapshot.onWantedTrack) return
        RoomPlayer.setRate(1.0)
        RoomPlayer.seekTo(targetPosition(row))
        RoomPlayer.play()
    }

    /**
     * The track the loop has handed to the player.
     *
     * Out here rather than inside the loop because the socket needs it too: it
     * is the difference between resuming the song the host is playing and
     * resuming whatever was loaded before they skipped.
     */
    @Volatile
    private var loaded: String = ""

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
        loaded = ""
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


        /** When the current settling window opened, so it cannot be endless. */
        var acquireSince = 0L

        /** Until when a reading would describe a jump still in flight. */
        var settleUntil = 0L

        /** How many times this track has been handed back to the page. */
        var reloads = 0
        var lastReloadAt = 0L

        /**
         * A track we started at its beginning, and are therefore hearing all
         * of.
         *
         * While this is the track in hand the gap is closed by speed alone. A
         * jump forward here would skip seconds nobody has heard yet, which is
         * the one thing the room is not allowed to cost anybody. Falling a
         * little further behind is free; the delay exists to absorb exactly
         * this.
         */
        var heardFrom = ""

        /**
         * The moment the room begins the track now in hand, or zero once it
         * has begun. Held silent until then.
         */
        var beginAt = 0L

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
                /*
                 * Their song has changed and ours has not finished.
                 *
                 * Running behind means exactly this: when the host moves on,
                 * the last few seconds of the previous track are still to play
                 * here. Loading the new one now would cut them off, and they
                 * are as much music as the next song's opening. So the old one
                 * is allowed to finish, and the new one is fetched in the time
                 * that buys — which is what the delay was for.
                 *
                 * Fetching costs a little more than the tail it is hidden
                 * behind, so the room ends up a shade further back after every
                 * change. That is walked off by speed over the following
                 * minute and nobody hears it happen.
                 */
                val snapshotNow = RoomPlayer.snapshot.value
                // Judged by our own player, not by where the host has got to.
                // The tail is however much of the last song is left here, and
                // if the room has drifted a little further back that is more
                // than the delay, not exactly the delay. Fetching the next
                // song stops this one, so it waits until this one is genuinely
                // done rather than until a number says it ought to be.
                val nearlyOver = snapshotNow.durationMs > 0 &&
                    snapshotNow.positionMs >= snapshotNow.durationMs - END_OF_TRACK_MS / 4
                val stillFinishing = beginAt == 0L &&
                    loadedFingerprint.isNotBlank() &&
                    hostPosition(host) < ROOM_LAG_MS + LONGEST_TAIL_MS &&
                    snapshotNow.playing &&
                    snapshotNow.onWantedTrack &&
                    !snapshotNow.strayed &&
                    !nearlyOver
                if (stillFinishing) {
                    publish(hostId, handle, FollowState.InStep(ROOM_LAG_MS), host)
                    delay(SETTLING_TICK_MS)
                    continue
                }
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
                // Where to start matters more than starting quickly. A
                // reading this old would put the track down somewhere nobody
                // is, and the jump back afterwards is heard by everybody.
                if (host.isPlaying && ageOf(host) > TOO_OLD_TO_START_MS) {
                    publish(hostId, handle, FollowState.CatchingUp, host)
                    delay(SETTLING_TICK_MS)
                    continue
                }
                /**
                 * Walking in on a song, as opposed to being here when it
                 * started.
                 *
                 * The difference decides where the needle goes down, and it is
                 * the difference between missing something and not. A song
                 * that began while we were already in the room is ours from
                 * its first second, so it starts at its first second, however
                 * far ahead the host happens to be by then — the delay is
                 * absorbed by being a little further behind, which nobody
                 * hears, rather than by skipping, which everybody does. A song
                 * already playing when we arrived had no opening for us to
                 * miss, so we join it where the room is.
                 */
                val walkedIn = loadedFingerprint.isBlank()
                val from = if (walkedIn) targetPosition(host) else 0L

                loadedFingerprint = fingerprint
                loaded = fingerprint
                loadedId = id
                heardFrom = if (walkedIn) "" else fingerprint
                // Somebody who walked in has nothing to wait for; the song is
                // already running and they join it. A song that started while
                // we were here is begun by the whole room at once, so it is
                // fetched silently and held until that moment.
                beginAt = if (walkedIn) 0L else roomStartMoment(host)
                cover = Artwork.cached(host.title, host.artist)
                publish(hostId, handle, FollowState.Loading(host.title), host)
                RoomPlayer.setRate(1.0)
                if (beginAt > 0L) RoomPlayer.cue(id, from) else RoomPlayer.load(id, from)
                // After the load rather than before it: the music matters more
                // than the picture, and this can take a moment.
                if (cover == null) cover = Artwork.fetch(host.title, host.artist)
                acquireUntil = SystemClock.elapsedRealtime() + ACQUIRE_MS
                acquireSince = SystemClock.elapsedRealtime()
                silentSince = 0L
                lastCorrection = 0L
                settleUntil = 0L
                reloads = 0
                pendingFirstSync = true
                delay(SETTLING_TICK_MS)
                continue
            }

            // Fetched, silent, waiting for the moment the room begins it.
            // Every phone works the moment out for itself from the host's own
            // row, so they let go together rather than each starting whenever
            // its own download happened to finish.
            if (beginAt > 0L) {
                val wait = beginAt - ServerClock.nowMs()
                if (wait > 0) {
                    publish(hostId, handle, FollowState.CatchingUp, host)
                    delay(wait.coerceAtMost(SETTLING_TICK_MS))
                    continue
                }
                // Late to the moment, which means behind everybody who made
                // it. They have all started the same song at the same instant,
                // so the only thing standing between this phone and the rest
                // of the room is the seconds it was late by.
                val lateBy = -wait
                RoomPlayer.begin(0L)
                beginAt = 0L
                pendingFirstSync = false
                acquireSince = SystemClock.elapsedRealtime()
                if (lateBy > IN_STEP_MS) {
                    // Start closing it at the next look rather than sitting
                    // out the settle and the cooling-off first. Nothing is
                    // skipped to do it — the gap is walked off by speed — but
                    // there is no reason to spend eight seconds not starting.
                    acquireUntil = 0L
                    lastCorrection = 0L
                } else {
                    lastCorrection = SystemClock.elapsedRealtime()
                    acquireUntil = SystemClock.elapsedRealtime() + SETTLE_MS
                }
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
                val nowW = SystemClock.elapsedRealtime()
                val mayReload = reloads < RELOAD_ATTEMPTS && nowW - lastReloadAt > ACQUIRE_MS
                if (!mayReload && worthReloading(host.durationMs, targetPosition(host))) {
                    // Handed back three times and still playing something
                    // else. Reloading again only starts the same cycle over,
                    // and each attempt reopens the settling window, which is
                    // how a room could sit on "catching up" indefinitely.
                    RoomPlayer.pause()
                    publish(
                        hostId, handle,
                        FollowState.Stuck("The player will not keep \"${host.title}\"."), host,
                    )
                    delay(TICK_MS)
                    continue
                }
                if (!worthReloading(host.durationMs, targetPosition(host))) {
                    // Their song is nearly over and ours has already ended.
                    // Reloading the last few seconds only ends again, and the
                    // page starts something of its own each time round — which
                    // is how a listener ended up on a different Drake song from
                    // the host. Wait for whatever they play next instead.
                    RoomPlayer.pause()
                    publish(hostId, handle, FollowState.CatchingUp, host)
                } else {
                    publish(hostId, handle, FollowState.Loading(host.title), host)
                    RoomPlayer.load(loadedId, targetPosition(host))
                    reloads += 1
                    lastReloadAt = nowW
                    acquireUntil = SystemClock.elapsedRealtime() + ACQUIRE_MS
                    acquireSince = SystemClock.elapsedRealtime()
                    silentSince = 0L
                }
                delay(TICK_MS)
                continue
            }

            val now = SystemClock.elapsedRealtime()
            // Buffering is allowed to extend this, but not to own it. A page
            // that says it is buffering and never stops saying so used to mean
            // a listener watching "catching up" until they gave up.
            val settling = (now < acquireUntil || snapshot.buffering) &&
                (acquireSince == 0L || now - acquireSince < LONGEST_ACQUIRE_MS)
            if (settling) {
                // One correction that does not wait for the settle window.
                // The moment a freshly loaded track is genuinely playing, it
                // is put where the host is now rather than where the host was
                // when the load was issued. Everything after this can afford
                // to wait; this cannot, because waiting is what costs the
                // opening of the song.
                if (pendingFirstSync && snapshot.playing && !snapshot.buffering &&
                    snapshot.onWantedTrack && !snapshot.strayed
                ) {
                    val out = targetPosition(host) - localPosition(snapshot)
                    if (abs(out) > FIRST_SYNC_TOLERANCE_MS) RoomPlayer.seekTo(targetPosition(host))
                    pendingFirstSync = false
                    lastCorrection = now
                    acquireUntil = now + SETTLE_MS
                    acquireSince = now
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
                    RoomPlayer.load(loadedId, targetPosition(host))
                    acquireUntil = now + ACQUIRE_MS
                    acquireSince = now
                    silentSince = now
                }
                delay(TICK_MS)
                continue
            }
            silentSince = 0L

            // A reading taken while a jump is still landing describes where
            // the player was, not where it was sent, and correcting on it
            // produces a second jump for a gap already closed. That is what a
            // listener scrubbing back and forth actually is.
            if (now < settleUntil) {
                publish(hostId, handle, FollowState.CatchingUp, host)
                delay(SETTLING_TICK_MS)
                continue
            }

            val off = targetPosition(host) - localPosition(snapshot)

            // A jump forward on a track we began at its start would skip
            // seconds nobody has heard. Behind is allowed; missing is not.
            // Backwards is still fine, since hearing something twice loses
            // nobody anything, and so is any jump on a track we joined
            // midway, where there was never anything to miss.
            val wouldSkip = heardFrom == Fingerprint.of(host.title, host.artist, host.durationMs) &&
                off > 0
            if (abs(off) <= IN_STEP_MS) heardFrom = ""

            if (!wouldSkip && abs(off) > TOLERANCE_MS && now - lastCorrection > MIN_CORRECTION_GAP_MS) {
                // Too far to walk back. A jump is heard, which is why it is
                // rate limited and why everything else is walked instead.
                RoomPlayer.setRate(1.0)
                RoomPlayer.seekTo(targetPosition(host))
                lastCorrection = now
                settleUntil = now + SEEK_SETTLE_MS
            } else {
                // Including a gap too wide to walk that has just been jumped:
                // during the wait before another jump is allowed, leaning on
                // the speed is better than doing nothing at all.
                val rate = rateFor(off)
                if (abs(rate - snapshot.rate) > RATE_STEP) RoomPlayer.setRate(rate)
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
     * How fast to play, to close a gap without anybody hearing it happen.
     *
     * Seeking is the obvious answer and the wrong one for small numbers: the
     * music stops, skips and starts again, and doing that every time two
     * phones drift half a second apart is worse than the drift. A few per cent
     * of speed is inaudible with the pitch held, and it closes the gap and
     * then holds it closed.
     *
     * Aimed to converge over a few seconds and clamped hard, because the point
     * is to be unnoticed. Inside [IN_STEP_MS] there is nothing worth doing:
     * chasing the last fraction of a second only means never settling.
     */
    internal fun rateFor(offMs: Long): Double {
        if (abs(offMs) <= IN_STEP_MS) return 1.0
        val nudge = offMs.toDouble() / CLOSE_OVER_MS
        return 1.0 + nudge.coerceIn(-MAX_NUDGE, MAX_NUDGE)
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
    /** How old the host's reading is, in the shared clock. */
    internal fun ageOf(host: RemoteNowPlaying): Long {
        val takenAt = runCatching { Instant.parse(host.updatedAt).toEpochMilli() }.getOrNull()
            ?: return Long.MAX_VALUE
        return (ServerClock.nowMs() - takenAt).coerceAtLeast(0)
    }

    /**
     * The moment the whole room begins the host's current track.
     *
     * Worked out rather than announced. The host's row says where they are and
     * when that was true, so the track began at the difference between them,
     * and every phone reading the same row in the same clock arrives at the
     * same number without anybody having to send it. That is what makes the
     * room start together: nobody is waiting on a message, so nobody is late
     * by however long their message took.
     *
     * Zero when the row cannot be read, which means start when ready.
     */
    internal fun roomStartMoment(host: RemoteNowPlaying): Long {
        val takenAt = runCatching { Instant.parse(host.updatedAt).toEpochMilli() }.getOrNull()
            ?: return 0L
        val began = takenAt - host.positionMs
        return began + ROOM_LAG_MS + FETCH_ALLOWANCE_MS
    }

    /**
     * Where this phone should be: a set distance behind the host.
     *
     * Every comparison in the follow loop is against this rather than against
     * the host directly. Being level with the host is not the goal and never
     * was achievable; being a constant, unchanging distance behind them is,
     * and it sounds the same as being level because the distance never moves.
     */
    internal fun targetPosition(host: RemoteNowPlaying): Long =
        (hostPosition(host) - ROOM_LAG_MS).coerceAtLeast(0L)

    internal fun hostPosition(host: RemoteNowPlaying): Long {
        if (!host.isPlaying) return host.positionMs
        val takenAt = runCatching { Instant.parse(host.updatedAt).toEpochMilli() }.getOrNull()
            ?: return host.positionMs
        // Both ends of this subtraction are in the database's time. Using this
        // phone's would fold whatever the two clocks disagree by straight into
        // the answer, and two Android phones are routinely most of a second
        // apart without either of them being wrong.
        val elapsed = (ServerClock.nowMs() - takenAt).coerceIn(0, LONGEST_PROJECTION_MS)
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
