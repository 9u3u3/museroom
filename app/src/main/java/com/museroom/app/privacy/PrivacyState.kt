package com.museroom.app.privacy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The off switch.
 *
 * An app that records whatever happens to be playing will eventually record
 * something the person did not mean to share. That makes a visible, one-tap way
 * to stop recording part of the product rather than a setting buried three
 * screens down.
 *
 * Private mode stops the recording itself, not just the display. Nothing is
 * written locally and nothing is published, so there is no trail to leak later.
 */
class PrivacyState private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("museroom.privacy", Context.MODE_PRIVATE)

    private val _privateSession = MutableStateFlow(prefs.getBoolean(KEY_PRIVATE, false))
    val privateSession: StateFlow<Boolean> = _privateSession.asStateFlow()

    fun setPrivate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRIVATE, enabled).apply()
        _privateSession.value = enabled
    }

    companion object {
        private const val KEY_PRIVATE = "private_session"

        @Volatile private var instance: PrivacyState? = null

        fun get(context: Context): PrivacyState =
            instance ?: synchronized(this) {
                instance ?: PrivacyState(context.applicationContext).also { instance = it }
            }
    }
}
