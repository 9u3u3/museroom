package com.museroom.app.media

import android.content.Context

/** Which player to open a friend's track in, once the user has chosen. */
class PlayerPreference private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("museroom.player", Context.MODE_PRIVATE)

    var preferred: String?
        get() = prefs.getString(KEY, null)?.takeIf { Sources.isSupported(it) }
        set(value) {
            if (value == null) prefs.edit().remove(KEY).apply()
            else prefs.edit().putString(KEY, value).apply()
        }

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val KEY = "preferred_player"

        @Volatile private var instance: PlayerPreference? = null

        fun get(context: Context): PlayerPreference =
            instance ?: synchronized(this) {
                instance ?: PlayerPreference(context.applicationContext).also { instance = it }
            }
    }
}
