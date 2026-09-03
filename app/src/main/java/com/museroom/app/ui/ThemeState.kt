package com.museroom.app.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which skin to wear.
 *
 * Deliberately not the system setting. This design has a light theme it was
 * drawn in, and following the phone means half the people who open it see a
 * version nobody chose for them. Dark is there, but it is a choice.
 */
class ThemeState private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("museroom.theme", Context.MODE_PRIVATE)

    private val _dark = MutableStateFlow(prefs.getBoolean(KEY, false))
    val dark: StateFlow<Boolean> = _dark.asStateFlow()

    fun setDark(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _dark.value = on
    }

    companion object {
        private const val KEY = "dark"

        @Volatile private var instance: ThemeState? = null

        fun get(context: Context): ThemeState =
            instance ?: synchronized(this) {
                instance ?: ThemeState(context.applicationContext).also { instance = it }
            }
    }
}
