package com.museroom.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import androidx.core.net.toUri
import com.museroom.app.listener.MediaListenerService

/** What a player will let us do to it, discovered rather than assumed. */
data class PlayerCapability(
    val packageName: String,
    val installed: Boolean,
    val hasLiveSession: Boolean,
    /** The session accepts "play this search query", which is the interesting one. */
    val canPlayFromSearch: Boolean,
    val canPlayFromUri: Boolean,
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
            )
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /**
     * Asks [packageName] to play [title] by [artist].
     *
     * Returns how it went, because the two paths behave differently and the user
     * should be told which one they got.
     */
    fun play(context: Context, packageName: String, title: String, artist: String): PlayOutcome {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return PlayOutcome.Failed("Nothing to play.")

        val controller = activeControllers(context).firstOrNull { it.packageName == packageName }
        val actions = controller?.playbackState?.actions ?: 0L

        if (controller != null && actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) {
            runCatching { controller.transportControls.playFromSearch(query, null) }
                .onSuccess { return PlayOutcome.Started }
        }

        val link = deepLink(packageName, query) ?: return PlayOutcome.Failed("No way to open that player.")
        val intent = Intent(Intent.ACTION_VIEW, link).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .fold(
                onSuccess = { PlayOutcome.Opened },
                onFailure = {
                    // Without the package restriction Android can still find a handler.
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .fold({ PlayOutcome.Opened }, { PlayOutcome.Failed("Could not open that player.") })
                },
            )
    }

    /**
     * A search link rather than a track link. Without a resolved catalogue id
     * there is no way to name one exact song, so this lands the person on it
     * rather than in it.
     */
    private fun deepLink(packageName: String, query: String): Uri? {
        val encoded = Uri.encode(query)
        return when (packageName) {
            "com.spotify.music" -> "https://open.spotify.com/search/$encoded".toUri()
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "app.rvx.android.apps.youtube.music",
            -> "https://music.youtube.com/search?q=$encoded".toUri()
            else -> null
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

    /** The app was opened at a search for the song. One more tap needed. */
    data object Opened : PlayOutcome

    data class Failed(val reason: String) : PlayOutcome
}
