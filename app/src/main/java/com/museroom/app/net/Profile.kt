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
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("join_mode") val joinMode: String = "ask",
    @SerialName("on_global_board") val onGlobalBoard: Boolean = true,
    /** How many times other people have liked something you were playing. */
    @SerialName("likes_received") val likesReceived: Int = 0,
    @SerialName("proximity_enabled") val proximityEnabled: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
) {
    val who: Visibility
        get() = Visibility.entries.firstOrNull { it.key == visibility } ?: Visibility.Friends

    /** Whether anybody may walk into your room, or has to ask first. */
    val openToAll: Boolean get() = joinMode == "open"
}

private val HANDLE = Regex("^[a-z0-9_]{3,20}$")

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

    /**
     * Choosing your own name.
     *
     * Handles used to be made out of the email address, which put a piece of
     * everybody's real identity on a public leaderboard without anyone asking
     * for it. This is the way back: a name you picked, and nothing else about
     * you visible to a stranger.
     */
    suspend fun setHandle(handle: String): Result<Unit> {
        val wanted = handle.trim().lowercase()
        if (!HANDLE.matches(wanted)) {
            return Result.failure(
                IllegalArgumentException(
                    "Three to twenty characters: letters, numbers and underscores.",
                ),
            )
        }
        return io { token, me ->
            try {
                Supabase.patch("profiles", "id=eq.$me", buildJsonObject { put("handle", wanted) }, token)
            } catch (clash: SupabaseError) {
                // 23505 is the unique index doing its job, and "taken" is the
                // only part of that a person needs.
                if (clash.body.contains("23505") || clash.status == 409) {
                    error("$wanted is taken.")
                }
                throw clash
            }
            refresh()
            Unit
        }
    }

    suspend fun setVisibility(v: Visibility) = patch { put("visibility", v.key) }

    suspend fun setOnGlobalBoard(on: Boolean) = patch { put("on_global_board", on) }

    suspend fun setOpenToAll(open: Boolean) = patch { put("join_mode", if (open) "open" else "ask") }

    /**
     * A picture, from this phone's gallery.
     *
     * Stored under a folder named after the person storing it, which is what
     * stops anybody replacing somebody else's. The address carries a version so
     * that a new picture is a new address: without it the old one would sit in
     * every cache that had ever seen it.
     */
    suspend fun setAvatar(jpeg: ByteArray): Result<Unit> = io { token, me ->
        val path = "$me/pic.jpg"
        Supabase.upload("avatars", path, jpeg, "image/jpeg", token)
        val url = "${Supabase.url}/storage/v1/object/public/avatars/$path?v=${System.currentTimeMillis()}"
        Supabase.patch("profiles", "id=eq.$me", buildJsonObject { put("avatar_url", url) }, token)
        refresh()
        Unit
    }

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
