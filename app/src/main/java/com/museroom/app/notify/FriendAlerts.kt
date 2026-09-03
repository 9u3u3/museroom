package com.museroom.app.notify

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a friend putting a record on is worth telling you about.
 *
 * This is the one notification Museroom sends that nobody asked for: the other
 * two answer a question somebody is waiting on, and this one is just news. With
 * three friends it is pleasant. With thirty it is a phone that buzzes all
 * evening, and the fix for that cannot be a threshold guessed on somebody
 * else's behalf.
 *
 * So there are two controls rather than one. The switch turns the whole thing
 * off. Muting is for keeping it on and losing the one friend who plays
 * forty songs an afternoon. Both live on this phone; neither is anybody
 * else's business.
 */
class FriendAlerts private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("museroom.alerts", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _muted = MutableStateFlow(prefs.getStringSet(KEY_MUTED, emptySet()).orEmpty())
    val muted: StateFlow<Set<String>> = _muted.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    fun setMuted(userId: String, muted: Boolean) {
        val next = if (muted) _muted.value + userId else _muted.value - userId
        // A copy, because SharedPreferences hands back the same mutable set it
        // is holding and editing that one in place changes nothing on disk.
        prefs.edit().putStringSet(KEY_MUTED, next.toSet()).apply()
        _muted.value = next
    }

    fun isMuted(userId: String): Boolean = userId in _muted.value

    /** Whether this particular friend starting something is worth a message. */
    fun shouldAnnounce(userId: String): Boolean = _enabled.value && !isMuted(userId)

    companion object {
        private const val KEY_ENABLED = "friend_listening_alerts"
        private const val KEY_MUTED = "muted_friends"

        @Volatile private var instance: FriendAlerts? = null

        fun get(context: Context): FriendAlerts =
            instance ?: synchronized(this) {
                instance ?: FriendAlerts(context.applicationContext).also { instance = it }
            }
    }
}
