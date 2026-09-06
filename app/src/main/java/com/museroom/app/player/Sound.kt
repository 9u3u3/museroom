package com.museroom.app.player

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The choices about sound that Museroom actually honours.
 *
 * Every switch here is wired to something audible, which is the rule this
 * screen is held to. Quality is what the extractor is asked for. Levelling and
 * the bands are Android's own audio effects on the session the player writes
 * into. Skip silence is ExoPlayer's. The fade is drawn by [Playback] between
 * one track and the next.
 *
 * The values live in preferences rather than the database because they are
 * about this installation rather than about anybody's music, and because they
 * have to be readable before a database is open: a player built at boot to
 * answer a Bluetooth button should already be levelling.
 */
object Sound {

    private const val PREFS = "sound"
    private const val KEY_QUALITY = "quality"
    private const val KEY_SKIP_SILENCE = "skipSilence"
    private const val KEY_NORMALISE = "normalise"
    private const val KEY_FADE = "fadeSeconds"
    private const val KEY_BANDS = "bands"

    private var app: Context? = null

    private val _quality = MutableStateFlow(Streams.Quality.High)
    val quality: StateFlow<Streams.Quality> = _quality.asStateFlow()

    private val _skipSilence = MutableStateFlow(false)
    val skipSilence: StateFlow<Boolean> = _skipSilence.asStateFlow()

    private val _normalise = MutableStateFlow(false)
    val normalise: StateFlow<Boolean> = _normalise.asStateFlow()

    /**
     * How long a track takes to fade out and the next to fade in, in seconds.
     *
     * Zero is off and is the default, because a fade is a thing people either
     * want very much or find baffling when it arrives uninvited.
     */
    private val _fadeSeconds = MutableStateFlow(0)
    val fadeSeconds: StateFlow<Int> = _fadeSeconds.asStateFlow()

    /** The longest fade offered. Beyond this the end of a song is a guess. */
    const val MAX_FADE_SECONDS = 12

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val prefs = prefs() ?: return

        val saved = prefs.getString(KEY_QUALITY, null)
        val chosen = Streams.Quality.entries.firstOrNull { it.name == saved } ?: Streams.Quality.High
        _quality.value = chosen
        LocalPlayer.quality = chosen

        _skipSilence.value = prefs.getBoolean(KEY_SKIP_SILENCE, false)
        LocalPlayer.setSkipSilence(_skipSilence.value)

        _normalise.value = prefs.getBoolean(KEY_NORMALISE, false)
        Effects.normalising = _normalise.value

        _fadeSeconds.value = prefs.getInt(KEY_FADE, 0).coerceIn(0, MAX_FADE_SECONDS)

        // Stored as one string rather than five keys: they are only ever read
        // and written together, and a half-applied equalizer is a sound nobody
        // chose.
        prefs.getString(KEY_BANDS, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == Effects.bands.size }
            ?.forEachIndexed { band, decibels -> Effects.setBand(band, decibels) }
    }

    /**
     * Changes what the next track is asked for.
     *
     * Not the one playing. Swapping the file underneath somebody mid-song would
     * mean a gap and a re-fetch to hear a difference most people cannot hear on
     * a phone, and the setting is about what happens from now on.
     */
    fun choose(quality: Streams.Quality) {
        _quality.value = quality
        LocalPlayer.quality = quality
        prefs()?.edit()?.putString(KEY_QUALITY, quality.name)?.apply()
    }

    fun setSkipSilence(enabled: Boolean) {
        _skipSilence.value = enabled
        LocalPlayer.setSkipSilence(enabled)
        prefs()?.edit()?.putBoolean(KEY_SKIP_SILENCE, enabled)?.apply()
    }

    fun setNormalise(enabled: Boolean) {
        _normalise.value = enabled
        Effects.normalising = enabled
        prefs()?.edit()?.putBoolean(KEY_NORMALISE, enabled)?.apply()
    }

    fun setFade(seconds: Int) {
        val held = seconds.coerceIn(0, MAX_FADE_SECONDS)
        _fadeSeconds.value = held
        if (held == 0) LocalPlayer.setVolume(1f)
        prefs()?.edit()?.putInt(KEY_FADE, held)?.apply()
    }

    fun setBand(index: Int, decibels: Int) {
        Effects.setBand(index, decibels)
        rememberBands()
    }

    fun flattenBands() {
        Effects.flat()
        rememberBands()
    }

    private fun rememberBands() {
        prefs()?.edit()?.putString(KEY_BANDS, Effects.levels.value.joinToString(","))?.apply()
    }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
