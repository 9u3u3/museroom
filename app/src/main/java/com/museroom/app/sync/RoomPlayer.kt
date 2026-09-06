package com.museroom.app.sync

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.museroom.app.player.Extraction
import com.museroom.app.player.InnerTube
import com.museroom.app.player.LocalPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The room's hand on the player.
 *
 * This used to hold the whole of YouTube Music in a hidden WebView and drive it
 * through JavaScript. It does not any more: Museroom has a player of its own,
 * and this is the thin layer that lets the room drive it without knowing that
 * anything changed. The surface is deliberately the one the room already spoke
 * to — cue, begin, seek, rate, a snapshot — because every invariant in
 * `FollowSession` and `TogetherHost` is written against it, and a surface that
 * holds means the room did not have to be rewritten to move house.
 *
 * Three of the room's oldest problems are gone rather than improved.
 *
 * There is no page queue to stray onto, so `strayed` is always false and the
 * check that guarded against playing a song nobody chose has nothing left to
 * guard. There are no ad breaks to survive, so `ad` is always false and
 * `adblock.js` is dead. And a position is a direct question to the player
 * rather than a number a page shouted a moment ago across a bridge, so the
 * tolerances that were sized partly to absorb that noise are now sized only for
 * the network.
 */
@SuppressLint("StaticFieldLeak")
object RoomPlayer {

    /**
     * Where the player is, in the shape the room already reads.
     *
     * [strayed] and [ad] survive as fields and are never true. They are left
     * here because the follow loop reads them and the loop is not what changed;
     * deleting them would mean editing the room to say the same thing.
     */
    data class Snapshot(
        val ready: Boolean = false,
        val videoId: String = "",
        val wanted: String = "",
        val title: String = "",
        val author: String = "",
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val state: Int = -1,
        val ad: Boolean = false,
        val rate: Double = 1.0,
        val strayed: Boolean = false,
        val detail: String = "",
        val takenAt: Long = 0,
    ) {
        val playing: Boolean get() = state == PLAYING
        val buffering: Boolean get() = state == BUFFERING
        val ended: Boolean get() = state == ENDED
        val onWantedTrack: Boolean get() = wanted.isNotBlank() && videoId == wanted
    }

    /** The numbers the room's code already speaks, kept as they were. */
    private const val PLAYING = 1
    private const val BUFFERING = 3
    private const val ENDED = 0
    private const val IDLE = -1

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var booted = false

    private var ticker: Job? = null

    /** True once there is a player to talk to, which is now immediate. */
    val started: Boolean get() = booted

    val context: Context? get() = appContext

    /**
     * Nothing to hang on a window any more.
     *
     * The WebView had to be attached to whichever activity was up, because
     * Chromium suspends media in a window nobody can see. A player that is not
     * a page has no window and no such problem, so these two stay only because
     * `MainActivity` calls them.
     */
    fun attach(activity: Activity) {
        prime(activity)
    }

    fun detach() = Unit

    fun prime(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        LocalPlayer.attach(context)
        Extraction.attach(context)
        booted = true
        publish()
    }

    /** Nothing to warm: there is no page to load before it can be asked. */
    fun warmUp() = Unit

    // ----------------------------------------------------------- driving --

    /** Fetch and play, which is what a listener joining partway through wants. */
    fun load(videoId: String, startMs: Long) {
        LocalPlayer.cue(LocalPlayer.Track(videoId), startMs)
        LocalPlayer.play()
        watch()
    }

    /** Fetch and hold, for a start everybody has agreed on. */
    fun cue(videoId: String, startMs: Long) {
        LocalPlayer.cue(LocalPlayer.Track(videoId), startMs)
        watch()
    }

    fun begin(positionMs: Long) {
        LocalPlayer.begin(positionMs)
        watch()
    }

    fun seekTo(positionMs: Long) = LocalPlayer.seekTo(positionMs)

    fun play() = LocalPlayer.play()

    fun pause() = LocalPlayer.pause()

    fun setRate(rate: Double) = LocalPlayer.setRate(rate)

    fun leave() {
        ticker?.cancel()
        ticker = null
        LocalPlayer.stop()
        publish()
    }

    /**
     * A video id for a song, by name.
     *
     * Used to mean asking a signed-in page to search itself. It is now the same
     * search the rest of the app uses, with the songs filter on, which matters
     * more here than anywhere: a music video is a different recording at a
     * different length, and a room where everybody has a different length is a
     * room the follow loop spends its life correcting.
     */
    suspend fun search(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        runCatching { InnerTube.search(query, limit = 1).firstOrNull()?.id }.getOrNull()
    }

    // ---------------------------------------------------------- watching --

    /**
     * Asks the player where it is, while a room is running.
     *
     * The engine keeps no timer of its own and answers events rather than
     * shouting, which is right for a screen that can ask when it needs to. A
     * room is the one caller that genuinely needs a fresh position on its own
     * schedule, because the whole job is comparing this phone's position with
     * somebody else's several times a second. So the room brings its own tick,
     * and stops it the moment it leaves.
     */
    private fun watch() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                LocalPlayer.tick()
                publish()
                delay(TICK_MS)
            }
        }
    }

    private const val TICK_MS = 200L

    private fun publish() {
        val inner = LocalPlayer.snapshot.value
        val track = LocalPlayer.current.value
        _snapshot.value = Snapshot(
            ready = inner.ready,
            videoId = inner.videoId,
            wanted = inner.wanted,
            title = track?.title.orEmpty(),
            author = track?.artist.orEmpty(),
            positionMs = inner.positionMs,
            durationMs = inner.durationMs,
            state = when {
                inner.ended -> ENDED
                inner.playing -> PLAYING
                inner.buffering -> BUFFERING
                else -> IDLE
            },
            // Never true, and kept so the room does not have to be told.
            ad = false,
            strayed = false,
            rate = inner.rate,
            detail = inner.detail,
            takenAt = inner.takenAtElapsed.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
        )
    }
}
