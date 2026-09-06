package com.museroom.app.player

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        Sound.attach(context)
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
        _queue.value = tracks
        _from.value = from
        go(at.coerceIn(tracks.indices))
    }

    /** Puts the rest of the queue in a different order, keeping this song. */
    fun shuffleRest() {
        val list = _queue.value
        val at = _index.value
        if (at < 0 || list.size < 3) return
        val played = list.take(at + 1)
        _queue.value = played + list.drop(at + 1).shuffled()
    }

    /** Adds to the end without disturbing what is playing. */
    fun enqueue(track: LocalPlayer.Track) {
        _queue.value = _queue.value + track
        if (_index.value < 0) go(0)
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
    fun next() {
        val at = _index.value + 1
        if (at in _queue.value.indices) {
            go(at)
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
            if (more.isNotEmpty()) _queue.value = _queue.value + more
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
        _from.value = ""
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
                if (snapshot.ended && !wasEnded) next()
                wasEnded = snapshot.ended
            }
        }
    }

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

    /** Searching, off the main thread, with a miss returning an empty list. */
    suspend fun search(query: String): List<LocalPlayer.Track> = withContext(Dispatchers.IO) {
        runCatching { InnerTube.search(query).map(::asTrack) }.getOrDefault(emptyList())
    }
}

/** The longest a queue is allowed to get by growing itself. */
private const val CEILING = 200

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
