package com.museroom.app.player

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The choices about sound that Museroom actually honours.
 *
 * Deliberately short. A settings screen full of switches that do nothing is
 * worse than a short one, and everything here is wired to something: the
 * quality is what the extractor is asked for, and clearing the shelf is what
 * Home builds its suggestions from.
 *
 * Skip silence, crossfade, normalisation and an equalizer belong on this screen
 * and are not built. They arrive with the audio processing that would make them
 * mean anything.
 */
object Sound {

    private const val PREFS = "sound"
    private const val KEY_QUALITY = "quality"

    private var app: Context? = null

    private val _quality = MutableStateFlow(Streams.Quality.High)
    val quality: StateFlow<Streams.Quality> = _quality.asStateFlow()

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val saved = prefs()?.getString(KEY_QUALITY, null)
        val chosen = Streams.Quality.entries.firstOrNull { it.name == saved } ?: Streams.Quality.High
        _quality.value = chosen
        LocalPlayer.quality = chosen
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

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
