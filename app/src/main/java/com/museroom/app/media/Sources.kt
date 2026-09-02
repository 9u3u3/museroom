package com.museroom.app.media

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a source stands with the user. */
enum class Consent { ALLOWED, BLOCKED, UNDECIDED }

/**
 * Consent, asked before anything is recorded.
 *
 * The earlier design tracked recognised players by default and let people delete
 * mistakes afterwards. That is the wrong way round. Someone playing something
 * they would not want on a leaderboard needs it to never have been written, not
 * to remember to go and remove it. So nothing is recorded from a source until
 * the user has said yes to that source.
 *
 * Browsers are decided per site, because one verdict covering every page someone
 * visits is no use. A browser session whose site cannot be identified stays
 * blocked, since there is nothing meaningful to consent to.
 */
class SourceRegistry private constructor(
    private val prefs: SharedPreferences,
    private val packageManager: PackageManager,
) {

    private val _decisions = MutableStateFlow(snapshot())
    val decisions: StateFlow<Map<String, Consent>> = _decisions.asStateFlow()

    init {
        migrateImplicitConsent()
    }

    fun consentFor(key: SourceKey): Consent = when {
        key.id in allowed() -> Consent.ALLOWED
        key.id in blocked() -> Consent.BLOCKED
        // A browser with no identifiable site can never be consented to, so it is
        // not a question worth asking. It simply does not count.
        key.isBrowser && key.site == null -> Consent.BLOCKED
        else -> Consent.UNDECIDED
    }

    fun isAllowed(key: SourceKey): Boolean = consentFor(key) == Consent.ALLOWED

    fun allow(key: SourceKey) = record(key, Consent.ALLOWED)

    fun block(key: SourceKey) = record(key, Consent.BLOCKED)

    /** Puts a source back to being asked about again. */
    fun forget(key: SourceKey) {
        prefs.edit()
            .putStringSet(KEY_ALLOWED, allowed() - key.id)
            .putStringSet(KEY_BLOCKED, blocked() - key.id)
            .apply()
        _decisions.value = snapshot()
    }

    private fun record(key: SourceKey, consent: Consent) {
        val allow = allowed().toMutableSet()
        val block = blocked().toMutableSet()
        if (consent == Consent.ALLOWED) {
            allow += key.id
            block -= key.id
        } else {
            allow -= key.id
            block += key.id
        }
        prefs.edit().putStringSet(KEY_ALLOWED, allow).putStringSet(KEY_BLOCKED, block).apply()
        _decisions.value = snapshot()
    }

    /** The player's own name where the system will tell us, its package otherwise. */
    fun label(packageName: String): String {
        KNOWN_NAMES[packageName]?.let { return it }
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0),
            ).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /**
     * Anyone upgrading from a build where recognised players counted automatically
     * keeps those players. Re-asking about Spotify on upgrade would be noise, and
     * silently switching them off would look like the app had broken.
     */
    private fun migrateImplicitConsent() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val previouslyOptedOut = prefs.getStringSet(LEGACY_OFF, emptySet()).orEmpty()
        val previouslyOptedIn = prefs.getStringSet(LEGACY_ON, emptySet()).orEmpty()
        val carriedOver = (KNOWN_NAMES.keys - previouslyOptedOut) + previouslyOptedIn

        prefs.edit()
            .putStringSet(KEY_ALLOWED, allowed() + carriedOver)
            .putStringSet(KEY_BLOCKED, blocked() + previouslyOptedOut)
            .putBoolean(KEY_MIGRATED, true)
            .apply()
        _decisions.value = snapshot()
    }

    private fun allowed(): Set<String> = prefs.getStringSet(KEY_ALLOWED, emptySet()).orEmpty()
    private fun blocked(): Set<String> = prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty()

    private fun snapshot(): Map<String, Consent> =
        allowed().associateWith { Consent.ALLOWED } + blocked().associateWith { Consent.BLOCKED }

    companion object {
        private const val PREFS = "museroom.sources"
        private const val KEY_ALLOWED = "allowed"
        private const val KEY_BLOCKED = "blocked"
        private const val KEY_MIGRATED = "migrated_to_consent"
        private const val LEGACY_ON = "opted_in"
        private const val LEGACY_OFF = "opted_out"

        /** Names for players Android may not label helpfully. */
        private val KNOWN_NAMES = linkedMapOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "app.revanced.android.apps.youtube.music" to "YouTube Music",
            "app.rvx.android.apps.youtube.music" to "YouTube Music",
            "com.apple.android.music" to "Apple Music",
            "com.soundcloud.android" to "SoundCloud",
            "com.amazon.mp3" to "Amazon Music",
            "deezer.android.app" to "Deezer",
            "com.aspiro.wamp" to "Tidal",
            "com.maxmpz.audioplayer" to "Poweramp",
            "com.jio.media.jiobeats" to "JioSaavn",
            "com.gaana" to "Gaana",
            "com.bsbportal.music" to "Wynk Music",
        )

        @Volatile private var instance: SourceRegistry? = null

        fun get(context: Context): SourceRegistry =
            instance ?: synchronized(this) {
                instance ?: SourceRegistry(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                    context.applicationContext.packageManager,
                ).also { instance = it }
            }
    }
}
