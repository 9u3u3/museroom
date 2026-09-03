package com.museroom.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@Serializable
data class ListenRequest(
    val id: Long = 0,
    @SerialName("from_user") val fromUser: String = "",
    @SerialName("to_user") val toUser: String = "",
    val status: String = "pending",
    val title: String = "",
    val artist: String = "",
    val fingerprint: String = "",
    @SerialName("source_track_id") val sourceTrackId: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val profiles: Who? = null,
) {
    @Serializable data class Who(val handle: String = "")

    val handle: String get() = profiles?.handle.orEmpty()
}

/**
 * Asking to listen along, and answering.
 *
 * No audio moves. Each phone plays the track from its own player; what travels
 * is the ask and the answer, which is what turns two people playing the same
 * song into one of them joining the other.
 */
class ListenRepository private constructor(context: Context) {

    private val auth = AuthRepository.get(context)

    /** Ask [host] to listen along to what they are on. */
    suspend fun ask(
        hostId: String,
        title: String,
        artist: String,
        fingerprint: String,
        sourceTrackId: String?,
    ): Result<Unit> = io { token, me ->
        val rows = buildJsonArray {
            add(
                buildJsonObject {
                    put("from_user", me)
                    put("to_user", hostId)
                    put("title", title)
                    put("artist", artist)
                    put("fingerprint", fingerprint)
                    put("source_track_id", sourceTrackId)
                },
            )
        }
        Supabase.insert("listen_requests", rows, token)
    }

    /** Requests waiting on you. */
    suspend fun inbox(): Result<List<ListenRequest>> = io { token, me ->
        val body = Supabase.select(
            "listen_requests",
            "to_user=eq.$me&status=eq.pending" +
                "&select=id,from_user,to_user,status,title,artist,fingerprint,source_track_id,created_at," +
                "profiles!listen_requests_from_user_fkey(handle)" +
                "&order=created_at.desc&limit=10",
            token,
        )
        Supabase.json.decodeFromString(ListSerializer(ListenRequest.serializer()), body)
    }

    /** Requests you sent that have been answered since you last looked. */
    suspend fun answered(sinceMs: Long): Result<List<ListenRequest>> = io { token, me ->
        val since = Instant.ofEpochMilli(sinceMs).toString()
        val body = Supabase.select(
            "listen_requests",
            "from_user=eq.$me&status=eq.accepted&responded_at=gte.$since" +
                "&select=id,from_user,to_user,status,title,artist,fingerprint,source_track_id,created_at," +
                "profiles!listen_requests_to_user_fkey(handle)" +
                "&order=responded_at.desc&limit=5",
            token,
        )
        Supabase.json.decodeFromString(ListSerializer(ListenRequest.serializer()), body)
    }

    suspend fun respond(id: Long, accept: Boolean): Result<Unit> = io { token, _ ->
        Supabase.patch(
            "listen_requests",
            "id=eq.$id",
            buildJsonObject {
                put("status", if (accept) "accepted" else "declined")
                put("responded_at", Instant.now().toString())
            },
            token,
        )
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
        @Volatile private var instance: ListenRepository? = null
        fun get(context: Context): ListenRepository =
            instance ?: synchronized(this) {
                instance ?: ListenRepository(context.applicationContext).also { instance = it }
            }
    }
}
