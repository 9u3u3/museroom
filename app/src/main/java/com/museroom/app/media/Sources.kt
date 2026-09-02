package com.museroom.app.media

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which players count as music.
 *
 * A fixed list of package names looked reasonable until a real device turned up
 * running a YouTube Music fork under its own package. Forks, regional builds and
 * the dozen other players people actually use will never all be in a list we
 * wrote in advance.
 *
 * So: recognised players are tracked on sight, anything else that publishes a
 * media session is surfaced to the user as a choice. That is more robust than
 * guessing, and it makes the privacy promise concrete rather than a claim.
 */
class SourceRegistry private constructor(
    private val prefs: SharedPreferences,
    private val packageManager: PackageManager,
) {

    private val _tracked = MutableStateFlow(currentlyTracked())
    val tracked: StateFlow<Set<String>> = _tracked.asStateFlow()

    fun isTracked(packageName: String): Boolean =
        packageName in optedIn() || (packageName in RECOGNISED && packageName !in optedOut())

    fun setTracked(packageName: String, tracked: Boolean) {
        val on = optedIn().toMutableSet()
        val off = optedOut().toMutableSet()
        if (tracked) {
            on += packageName
            off -= packageName
        } else {
            on -= packageName
            off += packageName
        }
        prefs.edit().putStringSet(KEY_ON, on).putStringSet(KEY_OFF, off).apply()
        _tracked.value = currentlyTracked()
    }

    /** The player's own name where the system will tell us, its package otherwise. */
    fun label(packageName: String): String {
        RECOGNISED[packageName]?.let { return it }
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0),
            ).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    fun isRecognised(packageName: String): Boolean = packageName in RECOGNISED

    private fun optedIn(): Set<String> = prefs.getStringSet(KEY_ON, emptySet()).orEmpty()
    private fun optedOut(): Set<String> = prefs.getStringSet(KEY_OFF, emptySet()).orEmpty()
    private fun currentlyTracked(): Set<String> = (RECOGNISED.keys - optedOut()) + optedIn()

    companion object {
        private const val PREFS = "museroom.sources"
        private const val KEY_ON = "opted_in"
        private const val KEY_OFF = "opted_out"

        /**
         * Players we enable without asking. Forks are listed explicitly because
         * their package differs while the metadata behaves the same.
         */
        private val RECOGNISED = linkedMapOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "app.revanced.android.apps.youtube.music" to "YouTube Music",
            "app.rvx.android.apps.youtube.music" to "YouTube Music",
            "com.google.android.apps.youtube.music.pwa" to "YouTube Music",
            "com.apple.android.music" to "Apple Music",
            "com.soundcloud.android" to "SoundCloud",
            "com.amazon.mp3" to "Amazon Music",
            "deezer.android.app" to "Deezer",
            "com.aspiro.wamp" to "Tidal",
            "com.maxmpz.audioplayer" to "Poweramp",
            "com.shazam.android" to "Shazam",
            "org.videolan.vlc" to "VLC",
            "com.bandcamp.android" to "Bandcamp",
            "com.jio.media.jiobeats" to "JioSaavn",
            "com.gaana" to "Gaana",
            "com.bsbportal.music" to "Wynk Music",
        )

        @Volatile
        private var instance: SourceRegistry? = null

        fun get(context: Context): SourceRegistry =
            instance ?: synchronized(this) {
                instance ?: SourceRegistry(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                    context.applicationContext.packageManager,
                ).also { instance = it }
            }
    }
}
