package com.museroom.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Museroom playing music itself.
 *
 * This is what replaces driving a YouTube Music page from the outside. The
 * player is ExoPlayer, the bytes come from a URL we resolved ourselves, and
 * nothing between here and the speaker has an opinion about what should play
 * next. Three of the room's oldest problems stop existing rather than getting
 * better: there is no page queue to stray onto, no ad break to delete, and no
 * bridge between asking where we are and being told.
 *
 * The surface is deliberately the one `sync/RoomPlayer.kt` already has, because
 * every invariant the room is built on is written against it. `cue` fetches
 * without making a sound, `begin` releases at a moment somebody else chose, and
 * a [Snapshot] says where we actually are.
 */
@SuppressLint("StaticFieldLeak")
@OptIn(UnstableApi::class)
object LocalPlayer {

    /**
     * Where we are, taken as one reading.
     *
     * Position is asked of ExoPlayer directly, so unlike the page this replaces
     * it is a millisecond rather than a rounded number that arrived a moment
     * ago. The room's tolerances were partly sized for that noise.
     */
    data class Snapshot(
        val ready: Boolean = false,
        val videoId: String = "",
        val wanted: String = "",
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val ended: Boolean = false,
        val rate: Double = 1.0,
        /** Said out loud, because a stall with no reason reads as a bug. */
        val detail: String = "",
        val takenAt: Long = 0,
    ) {
        val onWantedTrack: Boolean get() = wanted.isNotBlank() && videoId == wanted
    }

    /**
     * What the shade, the lock screen and the car need to draw a track.
     *
     * Carried with the media item rather than looked up when the notification
     * is built, because by then the only thing we would still have is an id.
     */
    data class Track(
        val id: String,
        val title: String = "",
        val artist: String = "",
        val durationMs: Long = 0,
        /** Empty when we only know the id, which is when the still has to do. */
        val cover: String = "",
    ) {
        val artworkUrl: String
            get() = cover.ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
    }

    private val _current = MutableStateFlow<Track?>(null)

    /** The track we last cued, for anything drawing it while it loads. */
    val current: StateFlow<Track?> = _current.asStateFlow()

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** What the listener asked for. Changing it never changes what is audible. */
    @Volatile
    var quality: Streams.Quality = Streams.Quality.High

    private val main = Handler(Looper.getMainLooper())

    private var app: Context? = null
    private var player: ExoPlayer? = null
    private var cache: SimpleCache? = null

    /**
     * The track we mean to be on.
     *
     * Kept outside the player because it survives the player being torn down
     * and rebuilt, and because a caller wants to know what we are aiming at
     * even during the seconds when nothing is loaded yet.
     */
    @Volatile
    private var wanted: String = ""

    /**
     * Clients that have just failed for a given track.
     *
     * A stream that resolved fine and then would not play is the signature of a
     * client being wrong about that recording, not of the track being dead. The
     * next attempt is told to ask somebody else.
     */
    private val burned = ConcurrentHashMap<String, MutableSet<String>>()

    // ------------------------------------------------------------------ setup --

    /** Called once, from the application. Cheap: nothing is built until asked. */
    fun attach(context: Context) {
        if (app == null) app = context.applicationContext
    }

    private fun require(): ExoPlayer {
        check(Looper.myLooper() == Looper.getMainLooper()) { "the player is a main-thread object" }
        player?.let { return it }
        val context = requireNotNull(app) { "LocalPlayer.attach was never called" }

        val built = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources(context)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // Museroom asks the system for audio focus and honours losing
                // it, which is the difference between a music app and a leak.
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        built.addListener(watcher)
        player = built
        tick()
        return built
    }

    /**
     * The stack the bytes come through.
     *
     * A resolving source on top so ExoPlayer can be handed video ids and only
     * pay for a lookup at the moment it needs bytes, a disk cache under it so a
     * track heard twice is fetched once, and OkHttp at the bottom because the
     * rest of the app already speaks it.
     */
    private fun dataSources(context: Context): DataSource.Factory {
        val evictor = LeastRecentlyUsedCacheEvictor(CACHE_BYTES)
        val store = cache ?: SimpleCache(
            File(context.cacheDir, "player"),
            evictor,
            StandaloneDatabaseProvider(context),
        ).also { cache = it }

        val network = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(
                OkHttpClient.Builder().build(),
            ),
        )

        val cached = CacheDataSource.Factory()
            .setCache(store)
            .setUpstreamDataSourceFactory(network)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return ResolvingDataSource.Factory(cached) { spec ->
            // Already in the cache: no lookup, no network, nothing to resolve.
            val id = spec.key ?: return@Factory spec
            if (store.isCached(id, spec.position, 1)) return@Factory spec

            val stream = Streams.resolve(id, quality, burned[id].orEmpty())
            val located = spec.withUri(Uri.parse(stream.url))
                .withRequestHeaders(spec.httpRequestHeaders + stream.headers)

            // Every read is a bounded range, and never a large one.
            //
            // Two refusals were measured against the real server, both answered
            // 403 with no explanation. A GET with no Range header at all is
            // refused, which is the shape ExoPlayer uses by default when it
            // wants a file from the start. So is a Range that covers most of
            // the track: a four megabyte file serves 0-1048575 and refuses
            // 0-2097151. Whatever the rule is, asking a megabyte at a time is
            // inside it, and a megabyte is about a minute of audio.
            val remaining = stream.contentLength - located.position
            val asked = when {
                located.length != C.LENGTH_UNSET.toLong() -> minOf(located.length, CHUNK_BYTES)
                remaining > 0 -> minOf(remaining, CHUNK_BYTES)
                else -> CHUNK_BYTES
            }
            located.subrange(0, asked)
        }
    }

    /** A session's worth of listening, not a library. Downloads come later. */
    private const val CACHE_BYTES = 512L * 1024 * 1024

    /** The largest range the server was willing to serve, measured not guessed. */
    private const val CHUNK_BYTES = 1L * 1024 * 1024

    // ----------------------------------------------------------------- driving --

    /**
     * Fetch a track and hold it, silently.
     *
     * This is the half of a scheduled start that can be done early. Everything
     * expensive happens here so that [begin] is only a decision to make sound.
     */
    fun cue(videoId: String, positionMs: Long = 0) = cue(Track(videoId), positionMs)

    fun cue(track: Track, positionMs: Long = 0) = onMain {
        retries = 0
        wanted = track.id
        _current.value = track
        val p = require()
        p.setPlaybackParameters(PlaybackParameters(1f))
        p.setMediaItem(
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri("museroom://${track.id}")
                // The cache and the resolver both key on this, so a track is one
                // thing to them however many times it is queued.
                .setCustomCacheKey(track.id)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title.ifBlank { "Museroom" })
                        .setArtist(track.artist)
                        .setArtworkUri(Uri.parse(track.artworkUrl))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build(),
                )
                .build(),
            positionMs.coerceAtLeast(0),
        )
        p.playWhenReady = false
        p.prepare()
        tick()
    }

    /**
     * Let go, at a position somebody else decided.
     *
     * Kept apart from [play] because they are different acts: play resumes
     * something the listener paused, begin starts a track everybody in a room
     * agreed to start together.
     */
    fun begin(positionMs: Long) = onMain {
        val p = require()
        if (positionMs > 0) p.seekTo(positionMs)
        p.playWhenReady = true
        tick()
    }

    fun play() = onMain { require().playWhenReady = true; tick() }

    fun pause() = onMain { require().playWhenReady = false; tick() }

    fun seekTo(positionMs: Long) = onMain { require().seekTo(positionMs.coerceAtLeast(0)); tick() }

    /**
     * Walk a small difference off with tempo rather than a seek.
     *
     * Pitch is held at 1.0 on purpose: a room closing half a second by running
     * four percent fast is inaudible, and the same correction with the pitch
     * dragged along is not.
     */
    fun setRate(rate: Double) = onMain {
        require().playbackParameters = PlaybackParameters(rate.toFloat(), 1f)
        tick()
    }

    fun stop() = onMain {
        wanted = ""
        _current.value = null
        player?.stop()
        player?.clearMediaItems()
        tick()
    }

    /** Gives the player and its cache back. Called when a room ends, not on pause. */
    fun release() = onMain {
        wanted = ""
        _current.value = null
        player?.removeListener(watcher)
        player?.release()
        player = null
        cache?.release()
        cache = null
        _snapshot.value = Snapshot()
    }

    /**
     * The player itself, for the one caller that needs the object rather than
     * the surface: the media session, which is what puts Museroom on the lock
     * screen, in the car and under a Bluetooth button.
     */
    internal fun exo(): ExoPlayer = require()

    private inline fun onMain(crossinline body: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) body() else main.post { body() }
    }

    // ---------------------------------------------------------------- watching --

    /**
     * How many times running we have re-asked for the current track's address.
     *
     * Reset the moment anything actually plays, so a track that recovers gets
     * its full allowance back rather than carrying a grudge into the next stall.
     */
    private var retries = 0

    private const val RETRIES = 4
    private const val TAG = "MuseroomPlayer"

    private val watcher = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) = tick()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retries = 0
            tick()
        }
        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) = tick()
        override fun onPositionDiscontinuity(
            old: Player.PositionInfo,
            new: Player.PositionInfo,
            reason: Int,
        ) = tick()

        override fun onPlayerError(error: PlaybackException) {
            val id = wanted
            val player = player
            if (id.isBlank() || player == null) return

            // A URL that resolved cleanly and then stopped serving is the
            // ordinary weather here, not a broken track. These links are signed,
            // they expire, the edge that issued one stops answering for it, and
            // a long track outlives its own address. So the answer is thrown
            // away and asked for again, from where we had got to.
            val resumeAt = player.currentPosition.coerceAtLeast(0)
            Streams.forget(id)

            if (retries < RETRIES) {
                retries++
                Log.w(TAG, "$id stalled at ${resumeAt}ms (${error.errorCodeName}), asking again")
                player.seekTo(resumeAt)
                player.prepare()
                return
            }

            // Out of patience. Blame the client that kept producing bad URLs so
            // the next track is asked of somebody else, and say what happened
            // rather than falling silent.
            Streams.cached(id)?.client?.let { burned.getOrPut(id) { mutableSetOf() }.add(it) }
            Log.w(TAG, "$id gave up after $RETRIES tries: ${error.errorCodeName}")
            _snapshot.value = _snapshot.value.copy(
                detail = "Could not keep this playing",
                takenAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * One reading, taken now.
     *
     * There is no polling loop here. A caller that wants a position asks for it,
     * and the events above cover every change the player makes on its own. The
     * page this replaces had to be asked four times a second because it could
     * only answer by shouting.
     */
    fun tick() = onMain {
        val p = player
        if (p == null) {
            _snapshot.value = Snapshot()
            return@onMain
        }
        val duration = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        _snapshot.value = Snapshot(
            ready = p.playbackState != Player.STATE_IDLE,
            videoId = p.currentMediaItem?.mediaId.orEmpty(),
            wanted = wanted,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            ended = p.playbackState == Player.STATE_ENDED,
            rate = p.playbackParameters.speed.toDouble(),
            detail = when (p.playbackState) {
                Player.STATE_IDLE -> "nothing loaded"
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_ENDED -> "track finished"
                else -> ""
            },
            takenAt = System.currentTimeMillis(),
        )
    }

    /** Forgets which clients failed, so a track is given a clean try again. */
    fun forgiveClients(videoId: String) {
        burned.remove(videoId)
    }
}
