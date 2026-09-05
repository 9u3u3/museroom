package com.museroom.app.sync

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import com.museroom.app.media.Artwork
import com.museroom.app.media.NowPlaying
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.media.ROOM_PACKAGE
import com.museroom.app.media.TrackResolver
import com.museroom.app.media.pickActive
import com.museroom.app.net.ServerClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The host as a client of their own room.
 *
 * A broadcast room reads whatever music app the host already had open and
 * plays a copy of it, three seconds behind, for everybody else. That distance
 * is not a shortcoming to be tuned away: a listener is told a song exists at
 * the instant it starts and cannot have fetched it yet, so a room level with a
 * native host would have to skip the opening or hear it twice. Running behind
 * on purpose turns the gap into a budget spent before the song rather than
 * during it.
 *
 * It is also the wrong deal when two people are in the same room with both
 * phones out loud. Then the host being three seconds early is the entire
 * problem, and there is no amount of correcting on the listener's side that
 * fixes it, because the thing that is ahead is a player Museroom does not own.
 *
 * So together mode changes what is playing rather than how it is chased.
 * Nobody's music app is the speaker, the host's included. Every phone in the
 * room is a [RoomPlayer] cued to the same recording and released on the same
 * moment in the database's clock, which is the arrangement every serious
 * multi-room system arrives at. Being level then is not a trick; it is simply
 * what happens, because nothing is ahead of the clock.
 *
 * Version 5.3 tried the other way round — hold the host's own Spotify at a
 * track change so the room could catch up — and it was removed in 5.5. A
 * third-party transport control is a request, not a command, and one that is
 * declined leaves the host silent for nothing.
 */
object TogetherHost {

    /**
     * How long the room is given to fetch a song before it starts.
     *
     * The same budget a broadcast listener already gets, and it buys the same
     * thing: every phone has the recording in hand before the moment arrives,
     * so nobody has to skip into a song that started without them. It is heard
     * as a short silence between tracks, which is the honest shape of what is
     * happening — the room waiting for itself.
     *
     * The ceiling on it is the point. A phone that has gone away must not be
     * able to hold everybody in silence, so this is a fixed allowance and not
     * a wait on a roster.
     */
    private const val ALLOWANCE_MS = 3_500L

    /**
     * The least notice anybody is given after being told the track exists.
     *
     * The allowance is counted from the moment the host cues, so an ordinary
     * track change spends most of it before the row is even written. Usually
     * that is right: the host's own fetch and the listeners' overlap. But when
     * the page is slow to say what it has fetched, the whole allowance can be
     * gone before anybody else has heard of the song, and then every listener
     * starts late and walks the gap off on every single track. So the moment
     * stretches rather than passing somebody by.
     */
    private const val MIN_NOTICE_MS = 2_000L

    /**
     * How long to wait for the page to say what it has cued.
     *
     * The id is enough to play a song and not enough to name one. A room row
     * carries a title and an artist because that is what everybody's screen
     * shows and what a like is recorded against, and the page knows both
     * within a few hundred milliseconds of the cue landing.
     */
    private const val DATA_WAIT_MS = 2_500L

    /** How often the host's own player is read and reported. */
    private const val TICK_MS = 500L

    /** How often an empty queue is looked at. Nobody is waiting on silence. */
    private const val QUEUE_TICK_MS = 400L

    /**
     * How close to the end counts as having heard the song out.
     *
     * A track that ends hands the page back its own queue, and the page starts
     * whatever it fancies; [RoomPlayer] stops it for straying, but by then the
     * id has already changed and the end is no longer visible in the state.
     * So the end is noticed on the way to it rather than after it.
     */
    private const val END_OF_TRACK_MS = 600L

    /**
     * Something the host has asked for, and the id once anything has found one.
     *
     * [beganAt] is the moment, in the shared clock, that this recording
     * notionally started — which is only ever set for the track being carried
     * across when the mode is turned on mid-song. Everything the host asks for
     * afterwards begins at its beginning, so it is zero and means the top.
     */
    data class Upcoming(val label: String, val id: String = "", val beganAt: Long = 0L)

    /** What the room is playing, once the page has said what it is. */
    private data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    sealed interface TogetherState {
        /** Not on. The host's own music app is the room, as it always was. */
        data object Off : TogetherState

        /** On, with nothing to play. The room is waiting to be given a song. */
        data object Empty : TogetherState

        data class Finding(val query: String) : TogetherState

        /** Fetched and silent, waiting for the moment the whole room begins it. */
        data class Starting(val title: String) : TogetherState

        data class Playing(val title: String, val artist: String) : TogetherState

        data class Stuck(val reason: String) : TogetherState
    }

    private val _on = MutableStateFlow(false)

    /** Whether this phone is hosting a room it is also a client of. */
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private val _state = MutableStateFlow<TogetherState>(TogetherState.Off)
    val state: StateFlow<TogetherState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<Upcoming>>(emptyList())
    val queue: StateFlow<List<Upcoming>> = _queue.asStateFlow()

    /**
     * Whether a music app on this phone is still making a sound.
     *
     * Worth saying out loud, and worth saying continuously rather than once.
     * Museroom asks the other app to stop on the way in and cannot make it,
     * so the honest thing is to keep showing whether it listened.
     */
    private val _elsewhere = MutableStateFlow(false)
    val playingElsewhere: StateFlow<Boolean> = _elsewhere.asStateFlow()

    /**
     * The moment the room begins the track now in hand, in the shared clock,
     * or zero when there is nothing scheduled.
     *
     * Read by [SyncEngine] on its way out, because it belongs in the row
     * rather than in a message: a listener working the moment out of a written
     * timestamp is never late by however long a message took to arrive.
     */
    @Volatile
    var startsAt: Long = 0L
        private set

    @Volatile
    var startPositionMs: Long = 0L
        private set

    /** What the room row should call this mode. The column takes one of two words. */
    val modeName: String get() = if (_on.value) "together" else "broadcast"

    /** The scheduled moment as the database writes times, or null when there is none. */
    val startsAtIso: String? get() = startsAt.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).toString() }

    @Volatile private var current: Track? = null
    @Volatile private var cover: android.graphics.Bitmap? = null
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var conductor: Job? = null
    private var focus: AudioFocusRequest? = null

    // ---- turning it on and off -------------------------------------------

    /**
     * Take the room onto Museroom's own player.
     *
     * The handover, in order, because the order is the whole of it: whatever
     * is playing natively is read before anything is asked to stop, since a
     * paused app may take its session away with it and then there is nothing
     * left to copy.
     */
    @Synchronized
    fun start(context: Context) {
        if (scope != null) return
        // The same one-player rule from the other side: taking the room onto
        // your own phone means leaving whoever's room you were in.
        FollowSession.stop()
        val app = context.applicationContext
        appContext = app
        _on.value = true
        _state.value = TogetherState.Empty
        // Before a single row is written. From here the room is the only thing
        // on this phone that counts as playing, whether or not the app we are
        // taking over from agrees to stop.
        NowPlayingRepository.setRoomOnly(true)

        RoomPlayer.prime(app)
        RoomPlayer.warmUp()
        TrackResolver.searcher = { title, artist -> RoomPlayer.search(title, artist) }
        RoomService.start(app, "")

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        // Before anything is scheduled. A moment written in a clock nobody
        // else keeps is a moment nobody else can meet.
        newScope.launch { ServerClock.sync() }
        newScope.launch { mind() }
        newScope.launch { handOver(app) }
    }

    /**
     * Give the room back to whatever music app the host was using.
     *
     * Said out loud rather than left to be noticed. Everybody following is
     * holding themselves level with a player that is about to stop being the
     * source of truth, and a row still claiming together mode would keep them
     * level with a native app that is three seconds ahead of where they can
     * possibly be.
     */
    @Synchronized
    fun stop() {
        // Called on the way into somebody else's room as well as by the person
        // turning it off, so it has to be free when there was nothing running.
        if (scope == null && !_on.value) return
        val app = appContext
        conductor = null
        scope?.cancel()
        scope = null
        _on.value = false
        _state.value = TogetherState.Off
        _queue.value = emptyList()
        _elsewhere.value = false
        current = null
        cover = null
        startsAt = 0L
        startPositionMs = 0L
        RoomPlayer.leave()
        NowPlayingRepository.setRoomOnly(false)
        NowPlayingRepository.setRoomPlayback(null)
        if (app == null) return
        dropFocus(app)
        // A listener left in somebody else's room still needs the service.
        if (FollowSession.following.value == null) RoomService.stop(app)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            handBack(app)
        }
    }

    /**
     * The row, corrected on the way out.
     *
     * The ordinary publish only writes when something it watches has changed,
     * and going from hosting a together room to reading Spotify need not
     * change the track, whether it is playing, or anything else it looks at.
     * So it is written here, unconditionally, because the one field that did
     * change is the one everybody following is steered by.
     */
    private suspend fun handBack(app: Context) {
        val sync = SyncEngine.get(app)
        val native = NowPlayingRepository.heard.value.pickActive()
        if (native != null) {
            sync.publishNowPlaying(native, native.positionAt(SystemClock.elapsedRealtime()))
        } else {
            sync.publishStopped()
        }
    }

    // ---- what the host does with it --------------------------------------

    /** Play this now, whatever is on. */
    @Synchronized
    fun playNow(query: String) {
        val wanted = query.trim()
        if (wanted.isBlank() || !_on.value) return
        _queue.value = listOf(Upcoming(wanted)) + _queue.value
        restart()
    }

    /** Play this after whatever is on. */
    @Synchronized
    fun enqueue(query: String) {
        val wanted = query.trim()
        if (wanted.isBlank() || !_on.value) return
        _queue.value = _queue.value + Upcoming(wanted)
    }

    @Synchronized
    fun remove(index: Int) {
        val list = _queue.value
        if (index !in list.indices) return
        _queue.value = list.filterIndexed { at, _ -> at != index }
    }

    /**
     * On to the next one.
     *
     * The one place in a room where cutting a track's tail is meant: the host
     * has decided this song is over, so it is over. A listener's tail rule
     * exists because they are behind and still have seconds of the last song
     * left; in together mode they are level, so there is nothing left to cut.
     */
    fun skip() {
        if (!_on.value) return
        RoomPlayer.pause()
        restart()
    }

    /** Stop and start, on the host's own copy, which everybody else follows. */
    fun toggle() {
        if (!_on.value) return
        val snapshot = RoomPlayer.snapshot.value
        if (snapshot.playing) {
            RoomPlayer.pause()
            return
        }
        // Never press play on a player that has strayed. It was stopped
        // because it had started somebody else's song, and starting it again
        // plays that song — to the whole room, on the host's authority.
        if (!snapshot.strayed) RoomPlayer.play()
    }

    // ---- the loop --------------------------------------------------------

    /**
     * Reads the host's own player and says what it finds.
     *
     * This is the piece that makes the host a client. Everything downstream —
     * the minutes, the leaderboard, the row every listener steers by, the
     * notification — reads the same session it always did, and the session
     * simply happens to be Museroom's player rather than Spotify's.
     */
    private suspend fun mind() {
        var reportedAt = 0L
        var lastKey = ""
        while (true) {
            val snapshot = RoomPlayer.snapshot.value
            val track = current
            _elsewhere.value = NowPlayingRepository.heard.value
                .any { !it.isRoom && it.isTracked && it.isPlaying }
            if (track != null) {
                val key = "${snapshot.videoId}|${snapshot.playing}"
                val now = SystemClock.elapsedRealtime()
                // Every change at once, and the position often enough to keep
                // a seek bar honest. Reporting on every reading instead would
                // put a write through the whole tracking chain several times a
                // second for a number that moves by itself anyway.
                if (key != lastKey || now - reportedAt > 1_000L) {
                    report()
                    lastKey = key
                    reportedAt = now
                }
            }
            delay(TICK_MS)
        }
    }

    /**
     * The handover itself.
     *
     * A track already playing is carried across rather than dropped, because
     * flipping the toggle in the middle of a song and having the song stop is
     * not what anybody meant by it.
     */
    private suspend fun handOver(app: Context) {
        // The unfiltered list. The room already owns what this phone reports,
        // so the app being taken over from is only visible through what the
        // system actually said.
        val native = NowPlayingRepository.heard.value.pickActive()
            ?.takeIf { !it.isRoom && it.title.isNotBlank() }
        // Focus first, because a well-behaved music app stops for it on its
        // own and that is quieter than being asked.
        takeFocus(app)
        NowPlayingRepository.askToPause()
        if (native != null) {
            // Where the song already is, kept rather than thrown away. Turning
            // the mode on in the middle of a track and having the track start
            // over is not what anybody meant by the switch, and for a room
            // that already had listeners in it, it would drag all of them back
            // to a beginning they heard a minute ago.
            val began = ServerClock.nowMs() - native.positionAt(SystemClock.elapsedRealtime())
            val id = TrackResolver.youtubeId(app, native.title, native.artist, native.durationMs)
            val label = listOf(native.title, native.artist).filter { it.isNotBlank() }
                .joinToString(" ")
            _queue.value = listOf(Upcoming(label, id.orEmpty(), began)) + _queue.value
        }
        restart()
    }

    /** Starts the run of tracks over, from whatever is at the head of the queue. */
    @Synchronized
    private fun restart() {
        val running = scope ?: return
        // Joined rather than merely cancelled. Cancellation is cooperative,
        // so an old run could still be a few statements from its next
        // suspension — and those statements set which track the room is on.
        val previous = conductor
        conductor = running.launch {
            previous?.cancelAndJoin()
            conduct()
        }
    }

    private suspend fun conduct() {
        val app = appContext ?: return
        while (true) {
            val next = awaitNext()
            try {
                play(app, next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                // A room that stops moving is worse than a room that says why.
                // Without this, one thrown lookup would leave the queue full
                // and nothing ever playing again.
                _state.value = TogetherState.Stuck(
                    failed.message ?: "That track could not be played.",
                )
            }
        }
    }

    /** The next thing to play, once there is one. Silence until then. */
    private suspend fun awaitNext(): Upcoming {
        while (true) {
            val head = takeHead()
            if (head != null) return head
            current = null
            NowPlayingRepository.setRoomPlayback(null)
            startsAt = 0L
            if (_state.value !is TogetherState.Stuck) _state.value = TogetherState.Empty
            delay(QUEUE_TICK_MS)
        }
    }

    @Synchronized
    private fun takeHead(): Upcoming? {
        val head = _queue.value.firstOrNull() ?: return null
        _queue.value = _queue.value.drop(1)
        return head
    }

    /**
     * One song, from finding it to hearing it out.
     *
     * The shape is the same one a listener runs, and deliberately so. Fetch
     * silently, agree a moment, let go on it. The host has no special
     * privilege in the second half of that: the whole point of the mode is
     * that they are waiting on the same instant as everybody else.
     */
    private suspend fun play(app: Context, item: Upcoming) {
        _state.value = TogetherState.Finding(item.label)
        val id = item.id.ifBlank { RoomPlayer.search(item.label, "").orEmpty() }
        if (id.isBlank()) {
            _state.value = TogetherState.Stuck("Could not find \"${item.label}\".")
            return
        }

        RoomPlayer.setRate(1.0)
        // Counted from the cue rather than from the row, so the host's own
        // fetch and everybody else's overlap instead of running end to end.
        val cued = ServerClock.nowMs()
        // Roughly where the song will be by the time the room lets go. Only an
        // estimate, because the exact place is not known until the moment is
        // settled; the seek in [RoomPlayer.begin] is what makes it exact.
        RoomPlayer.cue(id, positionAt(item, cued + ALLOWANCE_MS))
        val track = awaitData(id, item.label)
        cover = Artwork.cached(track.title, track.artist)

        // The moment first, then the track. The other order leaves a window
        // where the new song is on the row under the old song's moment, which
        // reads from outside as a room that stopped.
        val moment = maxOf(cued + ALLOWANCE_MS, ServerClock.nowMs() + MIN_NOTICE_MS)
        startPositionMs = positionAt(item, moment)
        startsAt = moment
        current = track
        // Written before the wait, not after it. The row is how anybody else
        // finds out there is a song coming, and the wait is the time they were
        // given to be ready for it.
        report()
        _state.value = TogetherState.Starting(track.title)
        if (cover == null) {
            cover = Artwork.fetch(track.title, track.artist)
            report()
        }

        while (true) {
            val wait = startsAt - ServerClock.nowMs()
            if (wait <= 0) break
            delay(wait.coerceAtMost(120L))
        }
        RoomPlayer.begin(startPositionMs)
        _state.value = TogetherState.Playing(track.title, track.artist)
        report()
        awaitEnd(id)
    }

    /** Where a track will be at a given moment: its beginning, unless carried over. */
    private fun positionAt(item: Upcoming, moment: Long): Long =
        if (item.beganAt <= 0L) 0L else (moment - item.beganAt).coerceAtLeast(0L)

    /**
     * Waits for the page to say what it has fetched, then gives up gracefully.
     *
     * Giving up is not a failure: the id is what actually plays, and it is
     * already published for everybody, so the worst case is a row labelled
     * with what the host typed instead of with the song's own name.
     */
    private suspend fun awaitData(id: String, fallback: String): Track {
        val deadline = SystemClock.elapsedRealtime() + DATA_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = RoomPlayer.snapshot.value
            if (snapshot.videoId == id && snapshot.title.isNotBlank() && snapshot.durationMs > 0) {
                return Track(id, snapshot.title, snapshot.author, snapshot.durationMs)
            }
            delay(120)
        }
        val snapshot = RoomPlayer.snapshot.value
        val onTrack = snapshot.videoId == id
        return Track(
            id = id,
            title = if (onTrack) snapshot.title.ifBlank { fallback } else fallback,
            artist = if (onTrack) snapshot.author else "",
            durationMs = if (onTrack) snapshot.durationMs else 0L,
        )
    }

    /**
     * Hears the song out.
     *
     * Noticed on the way to the end rather than at it. An ended track hands
     * the page back its own queue and the page starts something of its own,
     * which [RoomPlayer] stops for straying — but by then the id has moved on
     * and there is no ending left to see. So the last moment on our own track
     * is what counts as the end, and the player's own word for it is taken
     * whenever it arrives first.
     */
    private suspend fun awaitEnd(id: String) {
        var reachedEnd = false
        while (true) {
            val snapshot = RoomPlayer.snapshot.value
            if (snapshot.videoId == id && snapshot.durationMs > 0 &&
                snapshot.positionMs >= snapshot.durationMs - END_OF_TRACK_MS
            ) {
                reachedEnd = true
            }
            if (snapshot.ended) return
            if (reachedEnd && snapshot.videoId != id) return
            delay(TICK_MS)
        }
    }

    /**
     * The host's own playback, as an ordinary session.
     *
     * Nothing downstream is taught about together mode by this. It is the same
     * shape a listener's room already reports, which is what keeps the minutes,
     * the notification, the friends list and a like all reading one thing.
     */
    private fun report() {
        val track = current ?: return
        val snapshot = RoomPlayer.snapshot.value
        val onTrack = snapshot.videoId == track.id
        val begun = startsAt in 1..ServerClock.nowMs()
        NowPlayingRepository.setRoomPlayback(
            NowPlaying(
                packageName = ROOM_PACKAGE,
                sourceLabel = "Museroom room",
                isTracked = true,
                sourceTrackId = track.id,
                title = track.title,
                artist = track.artist,
                album = "",
                durationMs = if (track.durationMs > 0) track.durationMs else snapshot.durationMs,
                reportedPositionMs = if (onTrack && begun) snapshot.positionMs else startPositionMs,
                reportedAtElapsed = if (onTrack && begun) snapshot.takenAt
                else SystemClock.elapsedRealtime(),
                playbackSpeed = 1f,
                // A cued track is not playing, and saying otherwise would put
                // every listener's clock ahead of a song that has not started.
                isPlaying = onTrack && begun && snapshot.playing,
                audioContentType = AudioAttributes.CONTENT_TYPE_MUSIC,
                artwork = cover,
                rawMetadata = emptyMap(),
            ),
        )
    }

    // ---- the speaker -----------------------------------------------------

    /**
     * Asking the system for the sound.
     *
     * Not a lock, and not treated as one. Focus is how a music app is told to
     * stop politely, so it is the first and quietest half of the handover; the
     * change listener is deliberately empty, because a room losing focus to a
     * notification chime must not stop a song four other people are hearing.
     */
    private fun takeFocus(app: Context) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { audio.requestAudioFocus(request) }
        focus = request
    }

    private fun dropFocus(app: Context) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
    }
}
