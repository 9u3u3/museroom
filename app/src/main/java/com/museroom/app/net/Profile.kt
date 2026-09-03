package com.museroom.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class Visibility(val key: String) { Everyone("everyone"), Friends("friends"), Nobody("nobody") }

@Serializable
data class MyProfile(
    val id: String = "",
    val handle: String = "",
    @SerialName("display_name") val displayName: String = "",
    val visibility: String = "friends",
    @SerialName("on_global_board") val onGlobalBoard: Boolean = true,
    @SerialName("proximity_enabled") val proximityEnabled: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
) {
    val who: Visibility
        get() = Visibility.entries.firstOrNull { it.key == visibility } ?: Visibility.Friends
}

/** Your own row. Every setting on the You screen is a column here. */
class ProfileRepository private constructor(context: Context) {

    private val auth = AuthRepository.get(context)

    private val _profile = MutableStateFlow<MyProfile?>(null)
    val profile: StateFlow<MyProfile?> = _profile.asStateFlow()

    suspend fun refresh(): Result<MyProfile?> = io { token, me ->
        val body = Supabase.select("profiles", "id=eq.$me&select=*", token)
        Supabase.json.decodeFromString(ListSerializer(MyProfile.serializer()), body)
            .firstOrNull()
            .also { _profile.value = it }
    }

    suspend fun setVisibility(v: Visibility) = patch { put("visibility", v.key) }

    suspend fun setOnGlobalBoard(on: Boolean) = patch { put("on_global_board", on) }

    private suspend fun patch(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        io { token, me ->
            Supabase.patch("profiles", "id=eq.$me", buildJsonObject(build), token)
            refresh()
            Unit
        }

    private suspend fun <T> io(block: suspend (token: String, me: String) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                val me = auth.session.value?.userId ?: error("Sign in first.")
                val token = auth.validAccessToken() ?: error("Your session expired.")
                block(token, me)
            }
        }

    companion object {
        @Volatile private var instance: ProfileRepository? = null
        fun get(context: Context): ProfileRepository =
            instance ?: synchronized(this) {
                instance ?: ProfileRepository(context.applicationContext).also { instance = it }
            }
    }
}
