package com.museroom.app.player

import android.content.Context
import android.util.Log
import com.museroom.app.data.DownloadEntity
import com.museroom.app.data.MuseroomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeping a song on the phone.
 *
 * This is the one place a cached byte is allowed to outlive the moment it was
 * played, and the rules around it come straight out of why the player has no
 * cache of its own. A partial file that gets replayed is how a track came to
 * stop at the same second for ever, so nothing here is ever handed to the
 * player until it is whole: the bytes land in a `.part` file, the real name
 * only appears at the end, and the database row that says "this is playable
 * offline" is written after the rename rather than before it.
 *
 * Downloads are taken one at a time on purpose. Four at once finishes the
 * fourth sooner and the first much later, and the one somebody is about to get
 * on a train with is the first.
 */
object Downloads {

    /** A download in flight. Absent from [running] means done, failed or never asked. */
    data class Job(val id: String, val doneBytes: Long, val totalBytes: Long) {
        val fraction: Float
            get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _running = MutableStateFlow<Map<String, Job>>(emptyMap())
    val running: StateFlow<Map<String, Job>> = _running.asStateFlow()

    /**
     * Which songs are on the phone, held in memory as well as in the database.
     *
     * A row per song read from a flow would be right and would also mean a list
     * of forty songs asking the database forty questions while it scrolls.
     */
    private val _have = MutableStateFlow<Set<String>>(emptySet())
    val have: StateFlow<Set<String>> = _have.asStateFlow()

    private val _failed = MutableStateFlow<Map<String, String>>(emptyMap())
    val failed: StateFlow<Map<String, String>> = _failed.asStateFlow()

    private var app: Context? = null
    private val queue = Channel<LocalPlayer.Track>(Channel.UNLIMITED)
    private var pumping = false

    /** Ids somebody cancelled, checked between chunks so a stop is quick. */
    private val cancelled = mutableSetOf<String>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        scope.launch {
            _have.value = db().shelf().downloadedNow().toSet()
            sweep()
        }
        pump()
    }

    // ------------------------------------------------------------- the files --

    private fun folder(): File =
        File(requireNotNull(app) { "Downloads.attach was never called" }.filesDir, "offline")
            .also { it.mkdirs() }

    /** Where a finished song lives, whether or not it is there yet. */
    fun fileFor(songId: String): File = File(folder(), "$songId.audio")

    /**
     * The path to play from, or null to go to the network.
     *
     * Both halves are checked: the row says somebody meant to keep it and the
     * file says the bytes survived. A phone that ran out of space mid-write, or
     * a person who cleared the app's storage, breaks the second without
     * touching the first.
     */
    fun localPath(songId: String): String? {
        if (songId !in _have.value) return null
        val file = fileFor(songId)
        return if (file.isFile && file.length() > 0) file.absolutePath else null
    }

    /** Bytes held, asked of the disk rather than of the sum of the rows. */
    fun bytesOnDisk(): Long =
        runCatching { folder().listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    // -------------------------------------------------------------- the queue --

    /** Adds a song to the line, unless it is already there or already here. */
    fun start(track: LocalPlayer.Track) {
        if (track.id.isBlank()) return
        if (track.id in _have.value || track.id in _running.value) return
        synchronized(cancelled) { cancelled.remove(track.id) }
        _failed.value = _failed.value - track.id
        _running.value = _running.value + (track.id to Job(track.id, 0, 0))
        queue.trySend(track)
    }

    fun startAll(tracks: List<LocalPlayer.Track>) = tracks.forEach(::start)

    /** Stops one, whether it is in flight or still waiting its turn. */
    fun cancel(songId: String) {
        synchronized(cancelled) { cancelled.add(songId) }
        _running.value = _running.value - songId
        runCatching { File(folder(), "$songId.part").delete() }
    }

    /** Gives back the space and forgets the song was ever kept. */
    fun remove(songId: String) {
        scope.launch {
            runCatching { fileFor(songId).delete() }
            db().shelf().dropped(songId)
            _have.value = _have.value - songId
        }
    }

    fun removeEverything() {
        scope.launch {
            _running.value.keys.forEach(::cancel)
            runCatching { folder().listFiles()?.forEach { it.delete() } }
            db().shelf().dropAll()
            _have.value = emptySet()
        }
    }

    private fun pump() {
        if (pumping) return
        pumping = true
        scope.launch {
            for (track in queue) {
                val stopped = synchronized(cancelled) { cancelled.remove(track.id) }
                if (stopped || track.id in _have.value) {
                    _running.value = _running.value - track.id
                    continue
                }
                runCatching { fetch(track) }
                    .onFailure {
                        Log.w(TAG, "could not keep ${track.id}: ${it.message}")
                        _failed.value = _failed.value + (track.id to (it.message ?: "did not finish"))
                        runCatching { File(folder(), "${track.id}.part").delete() }
                    }
                _running.value = _running.value - track.id
            }
        }
    }

    // ----------------------------------------------------------- the transfer --

    /**
     * Fetches one song, in the size of pieces its address is willing to serve.
     *
     * The same bounded-range rule the player follows applies here, for the same
     * reason: some of these addresses refuse an unbounded request outright and
     * some refuse a large one, and which is which depends on the client that
     * issued the address. Asking in chunks is also what makes progress a real
     * number rather than a spinner.
     */
    private suspend fun fetch(track: LocalPlayer.Track) {
        val stream = Streams.resolve(track.id, Sound.quality.value)
        val total = stream.contentLength
        check(total > 0) { "the server did not say how long the file is" }

        val part = File(folder(), "${track.id}.part")
        part.delete()

        val chunk = if (stream.boundedRange) {
            stream.chunkBytes.takeIf { it > 0 } ?: CHUNK_BYTES
        } else {
            total
        }

        part.outputStream().use { sink ->
            var at = 0L
            while (at < total) {
                if (synchronized(cancelled) { track.id in cancelled }) {
                    part.delete()
                    return
                }
                val last = minOf(at + chunk, total) - 1
                val request = Request.Builder()
                    .url(stream.url)
                    .apply { stream.headers.forEach { (k, v) -> header(k, v) } }
                    .header("Range", "bytes=$at-$last")
                    .build()
                val read = http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "the server said ${response.code}" }
                    val body = response.body ?: error("the server sent nothing")
                    body.byteStream().copyTo(sink)
                }
                check(read > 0) { "the server stopped sending at $at bytes" }
                at += read
                _running.value = _running.value + (track.id to Job(track.id, at, total))
            }
        }

        // The real name appears only now. Everything that asks whether a song
        // is playable offline looks for this name, so it cannot see a file that
        // is still being written.
        val whole = fileFor(track.id)
        whole.delete()
        check(part.renameTo(whole)) { "could not put the file in its place" }

        db().library().remember(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            cover = track.cover,
        )
        db().shelf().kept(DownloadEntity(track.id, whole.length(), System.currentTimeMillis()))
        _have.value = _have.value + track.id
        Log.i(TAG, "kept ${track.id} (${whole.length()} bytes)")
    }

    /**
     * Throws away files nobody is claiming and rows with no file behind them.
     *
     * Both halves happen, because both go wrong in the wild. A `.part` left by
     * a phone that died mid-download is space nobody can use, and a row whose
     * file was wiped with the app's storage is a song that says it works with
     * the radio off and does not.
     */
    private suspend fun sweep() {
        val kept = _have.value
        runCatching {
            folder().listFiles()?.forEach { file ->
                if (file.name.endsWith(".part")) file.delete()
                else if (file.nameWithoutExtension !in kept) file.delete()
            }
        }
        val gone = kept.filterNot { fileFor(it).isFile }
        if (gone.isEmpty()) return
        gone.forEach { db().shelf().dropped(it) }
        _have.value = kept - gone.toSet()
    }

    private fun db(): MuseroomDatabase =
        MuseroomDatabase.get(requireNotNull(app) { "Downloads.attach was never called" })

    /** Used only when an address wants bounded reads without saying how big. */
    private const val CHUNK_BYTES = 2L * 1024 * 1024
    private const val TAG = "MuseroomDownloads"
}
