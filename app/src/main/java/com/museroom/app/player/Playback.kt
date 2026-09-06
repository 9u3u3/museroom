package com.museroom.app.player

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the screens talk to.
 *
 * [LocalPlayer] knows how to play one track. This knows what the person asked
 * for: a list, a place in it, and what happens when a track runs out. Keeping
 * the two apart is what lets a room drive the player without inheriting a queue
 * it does not want, because a room's idea of "next" comes from the host, not
 * from a list on this phone.
 */
object Playback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _queue = MutableStateFlow<List<LocalPlayer.Track>>(emptyList())
    val queue: StateFlow<List<LocalPlayer.Track>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    /** Where the current track came from, for the line above the title. */
    private val _from = MutableStateFlow("")
    val from: StateFlow<String> = _from.asStateFlow()

    /** What happens when the queue runs out, or when a track does. */
    enum class Repeat { Off, All, One }

    private val _repeat = MutableStateFlow(Repeat.Off)
    val repeat: StateFlow<Repeat> = _repeat.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /**
     * The queue in the order it arrived, kept for as long as shuffle is on.
     *
     * Shuffle that cannot be undone is not a shuffle, it is a destroyed album.
     * Holding the original means turning the switch off puts the record back in
     * its own order with the song that is playing still playing.
     */
    private var natural: List<LocalPlayer.Track> = emptyList()

    val snapshot get() = LocalPlayer.snapshot
    val current get() = LocalPlayer.current

    private var app: Context? = null
    private var watching = false

    /**
     * Whether a radio is already being fetched to lengthen the queue.
     *
     * Without this, hitting the last two tracks while one fetch is in flight
     * asks for a second radio and appends both, which is how a queue quietly
     * doubles.
     */
    private var extending = false

    fun attach(context: Context) {
        app = context.applicationContext
        LocalPlayer.attach(context)
        Library.attach(context)
        Downloads.attach(context)
        Sound.attach(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _shuffle.value = prefs.getBoolean(KEY_SHUFFLE, false)
        _repeat.value = Repeat.entries
            .firstOrNull { it.name == prefs.getString(KEY_REPEAT, null) } ?: Repeat.Off
        watchForTheEnd()
    }

    /**
     * Plays a list from one of its entries.
     *
     * The whole list is kept rather than the one track, because the difference
     * between a music player and a preview button is what happens when the song
     * finishes.
     */
    fun play(tracks: List<LocalPlayer.Track>, at: Int = 0, from: String = "") {
        if (tracks.isEmpty()) return
        natural = tracks
        _from.value = from
        if (_shuffle.value && tracks.size > 1) {
            // Shuffling a list somebody opened at a particular song still
            // starts with that song. They pressed a row, not a dice.
            val first = tracks[at.coerceIn(tracks.indices)]
            _queue.value = listOf(first) + (tracks - first).shuffled()
            _index.value = -1
            go(0)
        } else {
            _queue.value = tracks
            go(at.coerceIn(tracks.indices))
        }
    }

    /** Puts the rest of the queue in a different order, keeping this song. */
    fun shuffleRest() {
        val list = _queue.value
        val at = _index.value
        if (at < 0 || list.size < 3) return
        val played = list.take(at + 1)
        _queue.value = played + list.drop(at + 1).shuffled()
    }

    /**
     * Turns shuffle on or off, without interrupting the song.
     *
     * On, everything not yet played is reordered. Off, the queue goes back to
     * the order it came in and our place in it is found again by id, which is
     * the only thing about a track that survives being moved.
     */
    fun setShuffle(on: Boolean) {
        _shuffle.value = on
        remember(KEY_SHUFFLE, on)
        val playing = _queue.value.getOrNull(_index.value) ?: return
        if (on) {
            natural = _queue.value
            _queue.value = listOf(playing) + (_queue.value - playing).shuffled()
            _index.value = 0
        } else {
            val back = natural.takeIf { list -> list.any { it.id == playing.id } } ?: return
            _queue.value = back
            _index.value = back.indexOfFirst { it.id == playing.id }.coerceAtLeast(0)
        }
    }

    /** Off, then the whole queue, then this one song, which is the usual cycle. */
    fun cycleRepeat() {
        val next = when (_repeat.value) {
            Repeat.Off -> Repeat.All
            Repeat.All -> Repeat.One
            Repeat.One -> Repeat.Off
        }
        _repeat.value = next
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_REPEAT, next.name)?.apply()
    }

    private fun remember(key: String, value: Boolean) {
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(key, value)?.apply()
    }

    /**
     * Moves a row somebody dragged, and keeps the music where it was.
     *
     * The index of the playing track is recomputed rather than adjusted,
     * because a drag can pass over it in either direction and the arithmetic
     * for that is the kind that is wrong in one of the four cases.
     */
    fun move(from: Int, to: Int) {
        val list = _queue.value
        val playing = list.getOrNull(_index.value)
        val moved = reordered(list, from, to) ?: return
        _queue.value = moved
        natural = moved
        if (playing != null) _index.value = moved.indexOf(playing).coerceAtLeast(0)
    }

    /** Adds to the end without disturbing what is playing. */
    fun enqueue(track: LocalPlayer.Track) {
        _queue.value = _queue.value + track
        natural = _queue.value
        if (_index.value < 0) go(0)
    }

    /** Puts one song immediately after this one, which is a different promise. */
    fun playNext(track: LocalPlayer.Track) {
        val at = _index.value
        if (at < 0) {
            enqueue(track)
            return
        }
        _queue.value = _queue.value.toMutableList().also { it.add(at + 1, track) }
        natural = _queue.value
    }

    fun removeAt(position: Int) {
        val list = _queue.value
        if (position !in list.indices) return
        // Removing something behind us moves us back a place; removing the
        // track that is playing is a skip, not a deletion of the moment.
        val playing = _index.value
        _queue.value = list.toMutableList().also { it.removeAt(position) }
        when {
            _queue.value.isEmpty() -> stop()
            position < playing -> _index.value = playing - 1
            position == playing -> go(playing.coerceAtMost(_queue.value.lastIndex))
        }
    }

    /**
     * The next song, and there is always a next song.
     *
     * A queue used to be exactly what somebody pressed: the search results, or
     * the album. Running off the end of it stopped the music, which is right
     * for an album and wrong for everything else — nobody searches for one song
     * meaning "and then silence". So when the end is near the queue grows a
     * radio seeded from what is playing, the same way Home builds its
     * suggestions.
     */
    fun next(automatic: Boolean = false) {
        // A song ending is where a sleep timer set to "after this track" comes
        // due. Pressing skip is not, because somebody who is skipping is awake.
        if (automatic && SleepTimer.stopsHere()) {
            LocalPlayer.pause()
            return
        }
        if (automatic && _repeat.value == Repeat.One) {
            LocalPlayer.seekTo(0)
            LocalPlayer.play()
            return
        }
        val at = _index.value + 1
        if (at in _queue.value.indices) {
            go(at)
            return
        }
        // Repeating the queue is an instruction not to go looking for more
        // music, so it is checked before the radio that would otherwise grow
        // the list past its own end.
        if (_repeat.value == Repeat.All && _queue.value.isNotEmpty()) {
            go(0)
            return
        }
        val seed = _queue.value.lastOrNull()
        if (seed == null) {
            stop()
            return
        }
        scope.launch {
            val more = grow(seed)
            if (more.isEmpty()) stop() else go(at)
        }
    }

    /**
     * Puts more songs on the end, skipping any already in the queue.
     *
     * A radio seeded from a track usually starts with tracks around it, and
     * some of those are already here. Playing the same song twice in five
     * minutes is the kind of thing people notice and cannot explain.
     */
    private suspend fun grow(seed: LocalPlayer.Track): List<LocalPlayer.Track> {
        if (extending) return emptyList()
        extending = true
        return try {
            val more = newOnes(_queue.value, radio(seed.id))
            if (more.isNotEmpty()) {
                _queue.value = _queue.value + more
                natural = _queue.value
            }
            more
        } finally {
            extending = false
        }
    }

    /**
     * Back, or back to the beginning.
     *
     * Three seconds in, "previous" means the previous song; after that it means
     * this one from the top. Every player does this and every listener expects
     * it without being able to say so.
     */
    fun previous() {
        if (LocalPlayer.snapshot.value.positionMs > 3_000) {
            LocalPlayer.seekTo(0)
            return
        }
        val at = _index.value - 1
        if (at in _queue.value.indices) go(at) else LocalPlayer.seekTo(0)
    }

    fun toggle() {
        if (LocalPlayer.snapshot.value.playing) LocalPlayer.pause() else LocalPlayer.play()
    }

    fun seekTo(positionMs: Long) = LocalPlayer.seekTo(positionMs)

    fun stop() {
        _index.value = -1
        _queue.value = emptyList()
        natural = emptyList()
        _from.value = ""
        fading?.cancel()
        LocalPlayer.setVolume(1f)
        LocalPlayer.stop()
    }

    private fun go(at: Int) {
        val track = _queue.value.getOrNull(at) ?: return
        // Reach for more before the end rather than at it, so the queue is
        // never briefly empty while a radio is being fetched.
        if (at >= _queue.value.size - 2) {
            scope.launch { grow(_queue.value.last()) }
        }
        _index.value = at
        Library.played(track)
        LocalPlayer.forgiveClients(track.id)
        LocalPlayer.cue(track)
        LocalPlayer.begin(0)
        fadeIn()
        wake()
    }

    /**
     * Tells the system that audio is the point.
     *
     * Started rather than started-as-foreground on purpose. This only ever runs
     * because somebody pressed play in an app that is on screen, so the service
     * may go to the foreground when it has a notification to show, instead of
     * owing the system one within five seconds of a start that happened before
     * there was anything to say.
     */
    private fun wake() {
        val context = app ?: return
        runCatching { context.startService(Intent(context, PlayerService::class.java)) }
    }

    /** When a track runs out, the next one starts. That is the whole feature. */
    private fun watchForTheEnd() {
        if (watching) return
        watching = true
        scope.launch {
            var wasEnded = false
            LocalPlayer.snapshot.collect { snapshot ->
                if (snapshot.ended && !wasEnded) next(automatic = true)
                wasEnded = snapshot.ended
            }
        }
        watchForTheLastSeconds()
    }

    /**
     * The fade at the end of a track.
     *
     * Museroom has one player rather than two, so this is a fade rather than a
     * true crossfade: the outgoing track goes quiet, the incoming one comes up
     * from silence, and the two do not overlap. That is honest about what it is
     * and it fixes the thing people actually mind, which is a hard cut between
     * two songs recorded at different levels.
     *
     * A room turns it off outright. Everybody in a room is steering by one
     * position on one clock, and a volume ramp that happened on one phone and
     * not another is two people hearing different music at the same moment.
     */
    private fun watchForTheLastSeconds() {
        scope.launch {
            // The loop only exists while somebody has asked for a fade. Ten
            // times a second for the life of the app to discover that a setting
            // is still off is a cost nobody agreed to, and the default is off.
            Sound.fadeSeconds.collectLatest { seconds ->
                if (seconds == 0) return@collectLatest
                val window = seconds * 1000L
                while (isActive) {
                    delay(FADE_STEP_MS)
                    if (roomIsDriving) continue
                    val now = LocalPlayer.snapshot.value
                    if (!now.playing || now.durationMs <= 0) continue
                    val left = now.durationMs - now.positionMs
                    if (left in 1..window) {
                        LocalPlayer.setVolume((left.toFloat() / window).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    /** Brings a new track up from silence, over the same number of seconds. */
    private fun fadeIn() {
        fading?.cancel()
        val seconds = Sound.fadeSeconds.value
        if (seconds == 0 || roomIsDriving) {
            LocalPlayer.setVolume(1f)
            return
        }
        fading = scope.launch {
            val window = seconds * 1000L
            var elapsed = 0L
            LocalPlayer.setVolume(0f)
            while (isActive && elapsed < window) {
                delay(FADE_STEP_MS)
                elapsed += FADE_STEP_MS
                LocalPlayer.setVolume((elapsed.toFloat() / window).coerceIn(0f, 1f))
            }
            LocalPlayer.setVolume(1f)
        }
    }

    private var fading: Job? = null

    /**
     * Whether a room is steering, set by `sync/RoomPlayer.kt` as it takes over.
     *
     * The queue keeps its own idea of what is next while this is true, because
     * leaving a room should put somebody back where they were rather than in
     * silence.
     */
    @Volatile
    var roomIsDriving: Boolean = false

    /** The words for a track, off the main thread. */
    suspend fun lyrics(track: LocalPlayer.Track): Lyrics.Words? = withContext(Dispatchers.IO) {
        runCatching { Lyrics.of(track) }.getOrNull()
    }

    /** An album page, off the main thread, or null if it would not load. */
    suspend fun album(browseId: String): InnerTube.Album? = withContext(Dispatchers.IO) {
        runCatching { InnerTube.album(browseId) }.getOrNull()
    }

    suspend fun artist(browseId: String): InnerTube.Artist? = withContext(Dispatchers.IO) {
        runCatching { InnerTube.artist(browseId) }.getOrNull()
    }

    /** Somebody else's playlist, which reads as an album with mixed artists. */
    suspend fun playlistPage(browseId: String): InnerTube.Album? = withContext(Dispatchers.IO) {
        runCatching { InnerTube.playlist(browseId) }.getOrNull()
    }

    /** One search under one chip. A miss is an empty answer, never an error. */
    suspend fun searchFor(
        query: String,
        filter: InnerTube.Filter,
    ): InnerTube.Results = withContext(Dispatchers.IO) {
        runCatching { InnerTube.searchFor(query, filter) }.getOrDefault(InnerTube.Results())
    }

    /** What a page's rows turn into when somebody presses one. */
    fun tracksOf(found: List<InnerTube.Found>) = found.map(::asTrack)

    /** Songs that go with one you played, off the main thread. */
    suspend fun radio(seedId: String): List<LocalPlayer.Track> = withContext(Dispatchers.IO) {
        runCatching { InnerTube.radio(seedId).map(::asTrack) }.getOrDefault(emptyList())
    }

    fun asTrack(found: InnerTube.Found) = LocalPlayer.Track(
        id = found.id,
        title = found.title,
        artist = found.artist,
        album = found.album,
        durationMs = found.durationMs,
        cover = found.artworkUrl,
        artistId = found.artistId,
        albumId = found.albumId,
    )

    private const val PREFS = "playback"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_REPEAT = "repeat"

    /** Often enough that a fade is smooth, rarely enough to cost nothing. */
    private const val FADE_STEP_MS = 100L

    /** Searching, off the main thread, with a miss returning an empty list. */
    suspend fun search(query: String): List<LocalPlayer.Track> = withContext(Dispatchers.IO) {
        runCatching { InnerTube.search(query).map(::asTrack) }.getOrDefault(emptyList())
    }
}

/** The longest a queue is allowed to get by growing itself. */
private const val CEILING = 200

/**
 * A list with one entry moved, or null when the move means nothing.
 *
 * A free function for the same reason as [newOnes]: [Playback] holds a
 * main-thread scope and cannot be loaded off a device, and the arithmetic of a
 * drag is exactly the sort of thing that is wrong in one of its four cases and
 * should be able to be tested.
 */
fun <T> reordered(list: List<T>, from: Int, to: Int): List<T>? {
    if (from !in list.indices || to !in list.indices || from == to) return null
    return list.toMutableList().also { it.add(to, it.removeAt(from)) }
}

/**
 * What of [found] is worth adding to [queue].
 *
 * A radio seeded from a track usually opens with tracks around it, and some of
 * those are already here; playing the same song twice in five minutes is the
 * kind of thing people notice and cannot explain. The ceiling is the other
 * half: a queue that grew every time it neared its end would grow all
 * afternoon, and nobody is looking sixty songs ahead.
 *
 * A free function rather than a method, because [Playback] holds a main-thread
 * scope and cannot be loaded at all off a device. Logic worth testing should
 * not be locked inside something only a phone can construct.
 */
fun newOnes(
    queue: List<LocalPlayer.Track>,
    found: List<LocalPlayer.Track>,
): List<LocalPlayer.Track> {
    if (queue.size >= CEILING) return emptyList()
    val had = queue.mapTo(HashSet()) { it.id }
    val fresh = mutableListOf<LocalPlayer.Track>()
    for (track in found) {
        if (queue.size + fresh.size >= CEILING) break
        if (had.add(track.id)) fresh += track
    }
    return fresh
}
