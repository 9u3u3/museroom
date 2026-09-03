package com.museroom.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import com.museroom.app.listener.MediaListenerService
import kotlinx.coroutines.delay

/** What a player will let us do to it, discovered rather than assumed. */
data class PlayerCapability(
    val packageName: String,
    val installed: Boolean,
    val hasLiveSession: Boolean,
    /** The session accepts "play this search query", which is the interesting one. */
    val canPlayFromSearch: Boolean,
    val canPlayFromUri: Boolean,
    /** Whether it will follow somebody else's position. */
    val canSeek: Boolean = false,
)

/**
 * Making somebody else's player start a song.
 *
 * The notification listener access we already hold is two-way: the same
 * MediaController that reports what is playing also carries transport controls.
 * If a player advertises ACTION_PLAY_FROM_SEARCH we can hand it a song title and
 * it starts playing, with no account linking, no OAuth and no Premium tier.
 *
 * Not every player advertises it, so capability is probed rather than assumed,
 * and there is a deep link to fall back to. A deep link opens the app at the
 * song; only the session command actually starts it.
 */
object PlayerCommands {

    /** Roughly three seconds, which is long enough for a player to react. */
    private const val VERIFY_ATTEMPTS = 10
    private const val VERIFY_INTERVAL_MS = 300L

    /** Longer, because the app has to start before it can be told to play. */
    private const val LAUNCH_ATTEMPTS = 25

    fun capabilities(context: Context): List<PlayerCapability> {
        val controllers = activeControllers(context)
        return Sources.packages.map { pkg ->
            val controller = controllers.firstOrNull { it.packageName == pkg }
            val actions = controller?.playbackState?.actions ?: 0L
            PlayerCapability(
                packageName = pkg,
                installed = isInstalled(context, pkg),
                hasLiveSession = controller != null,
                canPlayFromSearch = actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L,
                canPlayFromUri = actions and PlaybackState.ACTION_PLAY_FROM_URI != 0L,
                canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            )
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /**
     * Asks [packageName] to play [title] by [artist].
     *
     * The command is verified rather than assumed. A player will happily accept
     * playFromSearch, advertise that it supports it, and then do nothing, and the
     * call does not throw when that happens. So we watch its session afterwards
     * and only claim success once it is actually playing the song. Anything else
     * falls through to opening the app.
     */
    suspend fun play(
        context: Context,
        packageName: String,
        title: String,
        artist: String,
        sourceTrackId: String? = null,
        durationMs: Long = 0,
    ): PlayOutcome {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return PlayOutcome.Failed("Nothing to play.")

        val controller = controllerFor(context, packageName)
        val actions = controller?.playbackState?.actions ?: 0L
        val before = controller?.currentTitle()

        // Best case: the player accepts a track id straight from its own session,
        // which needs no search and no guessing at which result is right.
        val uri = trackUri(packageName, sourceTrackId)
        if (controller != null && uri != null && actions and PlaybackState.ACTION_PLAY_FROM_URI != 0L) {
            runCatching { controller.transportControls.playFromUri(uri, null) }
            if (startedPlaying(context, packageName, title, before)) return PlayOutcome.Started
        }

        if (controller != null && actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) {
            runCatching { controller.transportControls.playFromSearch(query, null) }
            if (startedPlaying(context, packageName, title, before)) return PlayOutcome.Started
        }

        // No id from the host? Resolve one. An exact link opens the song itself,
        // where a search link only lands nearby and needs a tap.
        val resolvedId = sourceTrackId
            ?: TrackResolver.youtubeId(context, title, artist, durationMs)

        // Otherwise open the app. A track link lands on the song itself; a search
        // link only lands nearby, which is why the id is worth carrying.
        val links = trackLinks(packageName, resolvedId) + searchLinks(packageName, query)
        val exact = trackLinks(packageName, resolvedId).isNotEmpty()
        for (link in links) {
            if (!open(context, link, packageName)) continue
            // Opening a track usually leaves it loaded but paused. Pressing play
            // for the user is the last step towards not touching the other app.
            if (exact && pressPlay(context, packageName, title, before)) return PlayOutcome.Started
            return if (exact) PlayOutcome.OpenedExact else PlayOutcome.Opened
        }
        return PlayOutcome.Failed(
            "Could not open ${Sources.label(packageName)}. Is it installed?",
        )
    }

    /**
     * Where the local player is right now, extrapolated the same way every other
     * position in this app is.
     */
    fun localPosition(context: Context, packageName: String): Long? {
        val state = controllerFor(context, packageName)?.playbackState ?: return null
        val drift = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        val speed = if (state.state == PlaybackState.STATE_PLAYING) state.playbackSpeed else 0f
        return (state.position + drift * speed).toLong().coerceAtLeast(0L)
    }

    fun localTitle(context: Context, packageName: String): String? =
        controllerFor(context, packageName)?.currentTitle()

    /**
     * Moves the local player to [positionMs].
     *
     * Seeking is what makes following somebody possible without any of the
     * machinery that playing their audio would need, and it is far more widely
     * supported than the commands for starting a specific song: every player
     * whose notification has a scrubber accepts it.
     */
    fun seekTo(context: Context, packageName: String, positionMs: Long): Boolean {
        val controller = controllerFor(context, packageName) ?: return false
        val actions = controller.playbackState?.actions ?: 0L
        if (actions and PlaybackState.ACTION_SEEK_TO == 0L) return false
        return runCatching {
            controller.transportControls.seekTo(positionMs.coerceAtLeast(0))
        }.isSuccess
    }

    /**
     * Waits for the app we just opened to publish a session, then presses play.
     *
     * ACTION_PLAY is supported almost everywhere, unlike play-from-search, so
     * this works with players that ignore the richer commands entirely.
     */
    private suspend fun pressPlay(
        context: Context,
        packageName: String,
        title: String,
        before: String?,
    ): Boolean {
        repeat(LAUNCH_ATTEMPTS) {
            delay(VERIFY_INTERVAL_MS)
            val controller = controllerFor(context, packageName) ?: return@repeat
            val state = controller.playbackState
            if (state?.state == PlaybackState.STATE_PLAYING) return true
            if (state != null && state.actions and PlaybackState.ACTION_PLAY != 0L) {
                runCatching { controller.transportControls.play() }
            }
        }
        return startedPlaying(context, packageName, title, before)
    }

    /** A player-specific uri for an id the player itself published. */
    private fun trackUri(packageName: String, id: String?): Uri? {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("spotify:") || trimmed.startsWith("http") -> trimmed.toUri()
            packageName == "com.spotify.music" -> "spotify:track:$trimmed".toUri()
            else -> null
        }
    }

    private fun trackLinks(packageName: String, id: String?): List<Uri> {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return when (packageName) {
            "com.spotify.music" -> when {
                trimmed.startsWith("spotify:") -> listOf(trimmed.toUri())
                trimmed.startsWith("http") -> listOf(trimmed.toUri())
                else -> listOf(
                    "spotify:track:$trimmed".toUri(),
                    "https://open.spotify.com/track/$trimmed".toUri(),
                )
            }
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "app.rvx.android.apps.youtube.music",
            -> when {
                trimmed.startsWith("http") -> listOf(trimmed.toUri())
                // YouTube ids are eleven characters; anything else is not one.
                trimmed.length == 11 -> listOf("https://music.youtube.com/watch?v=$trimmed".toUri())
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    /** Watches the player's own session to see whether the command took effect. */
    private suspend fun startedPlaying(
        context: Context,
        packageName: String,
        wanted: String,
        before: String?,
    ): Boolean {
        val target = Fingerprint.title(wanted)
        repeat(VERIFY_ATTEMPTS) {
            delay(VERIFY_INTERVAL_MS)
            val controller = controllerFor(context, packageName) ?: return@repeat
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            val nowTitle = controller.currentTitle()
            if (!playing || nowTitle.isNullOrBlank()) return@repeat

            val matches = Fingerprint.title(nowTitle).let { it == target || it.contains(target) }
            if (matches || nowTitle != before) return true
        }
        return false
    }

    private fun open(context: Context, link: Uri, packageName: String): Boolean {
        val scoped = Intent(Intent.ACTION_VIEW, link).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(scoped) }.isSuccess) return true

        // Without the package restriction Android may still find a handler, which
        // covers forks whose package differs from the one that owns the link.
        val open = Intent(Intent.ACTION_VIEW, link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(open) }.isSuccess
    }

    private fun MediaController.currentTitle(): String? =
        metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

    private fun controllerFor(context: Context, packageName: String): MediaController? =
        activeControllers(context).firstOrNull { it.packageName == packageName }

    /**
     * A search link rather than a track link. Without a resolved catalogue id
     * there is no way to name one exact song, so this lands the person on it
     * rather than in it.
     */
    private fun searchLinks(packageName: String, query: String): List<Uri> {
        val encoded = Uri.encode(query)
        return when (packageName) {
            "com.spotify.music" -> listOf(
                "spotify:search:$encoded".toUri(),
                "https://open.spotify.com/search/$encoded".toUri(),
            )
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "app.rvx.android.apps.youtube.music",
            -> listOf("https://music.youtube.com/search?q=$encoded".toUri())
            else -> emptyList()
        }
    }

    private fun activeControllers(context: Context): List<MediaController> {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        val component = ComponentName(context, MediaListenerService::class.java)
        return runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
    }
}

sealed interface PlayOutcome {
    /** The player was commanded directly and should already be playing. */
    data object Started : PlayOutcome

    /** The app was opened on the exact song, but would not start itself. */
    data object OpenedExact : PlayOutcome

    /** The app was opened at a search for the song. One more tap needed. */
    data object Opened : PlayOutcome

    data class Failed(val reason: String) : PlayOutcome
}
