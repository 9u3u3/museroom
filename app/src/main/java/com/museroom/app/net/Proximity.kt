package com.museroom.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** Somebody in range, and what they are playing. */
@Serializable
data class NearbyListener(
    @SerialName("user_id") val userId: String,
    val handle: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("join_mode") val joinMode: String = "ask",
    val title: String = "",
    val artist: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("source_track_id") val sourceTrackId: String? = null,
    @SerialName("source_package") val sourcePackage: String = "",
) {
    val openToAll: Boolean get() = joinMode == "open"
}

/**
 * The server half of proximity: publishing our own token, and turning the tokens
 * we overhear into people.
 */
class ProximityApi private constructor(context: Context) {

    private val auth = AuthRepository.get(context)

    /** Publishes a token so others in range can resolve us for its lifetime. */
    suspend fun publish(token: String, expiresAtMs: Long): Result<Unit> = io { token1, me ->
        val rows = buildJsonArray {
            add(
                buildJsonObject {
                    put("token", token)
                    put("user_id", me)
                    put("expires_at", Instant.ofEpochMilli(expiresAtMs).toString())
                },
            )
        }
        Supabase.insert("proximity_beacons", rows, token1)
    }

    /** Withdraws every beacon we published. Called when the feature is switched off. */
    suspend fun withdraw(): Result<Unit> = io { token, me ->
        Supabase.delete("proximity_beacons", "user_id=eq.$me", token)
    }

    suspend fun setEnabled(enabled: Boolean): Result<Unit> = io { token, me ->
        Supabase.patch(
            "profiles",
            "id=eq.$me",
            buildJsonObject { put("proximity_enabled", enabled) },
            token,
        )
    }

    /** Asks who these tokens belong to. Unknown tokens simply return nothing. */
    suspend fun resolve(tokens: Collection<String>): Result<List<NearbyListener>> = io { token, _ ->
        if (tokens.isEmpty()) return@io emptyList()
        val body = buildJsonObject {
            put("tokens", JsonArray(tokens.take(64).map { JsonPrimitive(it) }))
        }
        val json = Supabase.rpc("resolve_nearby", body, token)
        Supabase.json.decodeFromString(ListSerializer(NearbyListener.serializer()), json)
    }

    private suspend fun <T> io(block: suspend (token: String, me: String) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                val me = auth.session.value?.userId ?: error("Sign in to use proximity.")
                val token = auth.validAccessToken() ?: error("Your session expired.")
                block(token, me)
            }
        }

    companion object {
        @Volatile private var instance: ProximityApi? = null

        fun get(context: Context): ProximityApi =
            instance ?: synchronized(this) {
                instance ?: ProximityApi(context.applicationContext).also { instance = it }
            }
    }
}
