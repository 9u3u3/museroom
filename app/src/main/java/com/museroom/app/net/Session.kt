package com.museroom.app.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A signed-in identity, as far as this device is concerned. */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    /** Wall-clock millis at which [accessToken] stops being accepted. */
    val expiresAtMs: Long,
) {
    fun expiringWithin(marginMs: Long = 60_000L): Boolean =
        System.currentTimeMillis() + marginMs >= expiresAtMs
}

/**
 * Where the session lives between launches. Plain preferences rather than
 * encrypted storage: the refresh token grants only this user's own rows, every
 * one of which is guarded by a policy in the database.
 */
class SessionStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("museroom.session", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(read())
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun save(session: Session) {
        prefs.edit()
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putString(KEY_USER, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putLong(KEY_EXPIRES, session.expiresAtMs)
            .apply()
        _session.value = session
    }

    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun read(): Session? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        return Session(
            accessToken = access,
            refreshToken = refresh,
            userId = user,
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
            expiresAtMs = prefs.getLong(KEY_EXPIRES, 0L),
        )
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_EXPIRES = "expires_at"

        @Volatile private var instance: SessionStore? = null

        fun get(context: Context): SessionStore =
            instance ?: synchronized(this) {
                instance ?: SessionStore(context.applicationContext).also { instance = it }
            }
    }
}
