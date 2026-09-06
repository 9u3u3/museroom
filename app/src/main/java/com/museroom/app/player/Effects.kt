package com.museroom.app.player

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The two things Museroom does to the sound after decoding it.
 *
 * Both are Android's own audio effects rather than anything of ours, attached
 * to the session the player is writing into. That matters for a reason worth
 * stating: an effect bound to a session id stops existing when that session
 * does, so every one of these has to be built again when the player is rebuilt,
 * and every call here is wrapped, because a device is allowed to simply not
 * have an equalizer and saying so quietly is better than taking the app down.
 *
 * Levels are kept here rather than inside the effect, so the screen has
 * something to draw before an effect exists and the settings survive the player
 * being released between sessions.
 */
object Effects {

    /**
     * The bands the screen draws.
     *
     * Five is what the design shows and what nearly every device offers. A
     * device with a different number is mapped onto these rather than redrawn,
     * because a settings screen whose shape depends on the handset is a
     * settings screen nobody can be told how to use.
     */
    val bands = listOf("60", "230", "910", "3.6k", "14k")

    /** Decibels, from -15 to +15, one per band. */
    private val _levels = MutableStateFlow(List(bands.size) { 0 })
    val levels: StateFlow<List<Int>> = _levels.asStateFlow()

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null

    @Volatile
    private var session: Int = 0

    /**
     * Binds to a new audio session, dropping whatever was bound to the old one.
     *
     * Called every time the player reports a session, which includes the first
     * time it has one at all and every rebuild after a release.
     */
    fun bind(audioSessionId: Int) {
        if (audioSessionId == session) return
        release()
        session = audioSessionId
        if (audioSessionId == 0) return

        equalizer = runCatching { Equalizer(0, audioSessionId) }
            .onFailure { Log.w(TAG, "no equalizer on this device: ${it.message}") }
            .getOrNull()
        _available.value = equalizer != null
        loudness = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()

        applyEqualizer()
        applyLoudness()
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        loudness = null
        session = 0
        _available.value = false
    }

    // --------------------------------------------------------------- the bands --

    fun setBand(index: Int, decibels: Int) {
        if (index !in _levels.value.indices) return
        _levels.value = _levels.value.toMutableList().also {
            it[index] = decibels.coerceIn(-MAX_DB, MAX_DB)
        }
        applyEqualizer()
    }

    fun flat() {
        _levels.value = List(bands.size) { 0 }
        applyEqualizer()
    }

    /** Whether anything is actually being changed, for the line under the title. */
    val flatNow: Boolean get() = _levels.value.all { it == 0 }

    /**
     * Writes our five numbers onto however many bands the device really has.
     *
     * A device with three bands gets our five spread across them and a device
     * with ten gets each of ours applied to the nearest two. Neither is exactly
     * what the slider says, and both are much closer to it than refusing to do
     * anything on hardware that is not five-band.
     */
    private fun applyEqualizer() {
        val eq = equalizer ?: return
        runCatching {
            eq.enabled = !flatNow
            val count = eq.numberOfBands.toInt()
            if (count <= 0) return
            val range = eq.bandLevelRange
            val floor = range[0].toInt()
            val ceiling = range[1].toInt()
            val ours = _levels.value
            for (band in 0 until count) {
                val position = if (count == 1) 0.0 else band.toDouble() / (count - 1)
                val mine = (position * (ours.size - 1)).toInt().coerceIn(ours.indices)
                val millibels = (ours[mine] * 100).coerceIn(floor, ceiling)
                eq.setBandLevel(band.toShort(), millibels.toShort())
            }
        }.onFailure { Log.w(TAG, "could not set the bands: ${it.message}") }
    }

    // ---------------------------------------------------------- the loudness --

    /**
     * How loud this recording says it is, when the extractor was told.
     *
     * YouTube ships a loudness figure per recording for exactly this purpose:
     * it is how much quieter than the reference this master is. Turning it back
     * up is what makes two tracks from two decades sit at the same level, and
     * it is a real number rather than a guess from listening to the output.
     */
    @Volatile
    var normalising: Boolean = false
        set(value) {
            field = value
            applyLoudness()
        }

    @Volatile
    private var trackLoudnessDb: Double? = null

    fun trackLoudness(db: Double?) {
        trackLoudnessDb = db
        applyLoudness()
    }

    private fun applyLoudness() {
        val enhancer = loudness ?: return
        runCatching {
            if (!normalising) {
                enhancer.setTargetGain(0)
                enhancer.enabled = false
                return
            }
            // The figure is how far below the reference this recording sits, so
            // the correction is its opposite. Boost only: pushing a loud master
            // down is what a limiter is for, and this is not one.
            val db = trackLoudnessDb ?: 0.0
            val gain = (-db).coerceIn(0.0, MAX_BOOST_DB)
            enhancer.setTargetGain((gain * 100).toInt())
            enhancer.enabled = gain > 0.05
        }.onFailure { Log.w(TAG, "could not level the track: ${it.message}") }
    }

    private const val MAX_DB = 15

    /**
     * The ceiling on making something louder.
     *
     * Above this the enhancer is not raising the music, it is raising the room
     * tone the recording was made in, and a very quiet master hauled up twenty
     * decibels sounds worse than the same master left quiet.
     */
    private const val MAX_BOOST_DB = 12.0
    private const val TAG = "MuseroomEffects"
}
