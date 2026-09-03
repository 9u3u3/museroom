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
import java.net.URLEncoder

@Serializable
data class Profile(
    val id: String,
    val handle: String,
    @SerialName("display_name") val displayName: String = "",
)

@Serializable
data class RemoteNowPlaying(
    val title: String = "",
    val artist: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("source_track_id") val sourceTrackId: String? = null,
    @SerialName("source_package") val sourcePackage: String = "",
)

@Serializable
private data class ProfileWithPlayback(
    val id: String,
    val handle: String,
    @SerialName("display_name") val displayName: String = "",
    // PostgREST embeds this as a single object, not an array: now_playing's
    // primary key is also its foreign key, so the relationship is one-to-one.
    @SerialName("now_playing") val nowPlaying: RemoteNowPlaying? = null,
)

/** A friend, and whatever they were last heard playing. */
data class Friend(
    val profile: Profile,
    val nowPlaying: RemoteNowPlaying?,
)

@Serializable
private data class FriendshipRow(
    @SerialName("user_a") val userA: String,
    @SerialName("user_b") val userB: String,
    val status: String,
    @SerialName("requested_by") val requestedBy: String,
)

/** A request waiting on you, or one you sent. */
data class PendingRequest(
    val profile: Profile,
    val incoming: Boolean,
)

/**
 * Friends, over PostgREST.
 *
 * Reads are deliberately thin. What a friend may see is decided by policy in the
 * database, so a query that asks for too much simply comes back with less rather
 * than leaking. That keeps the client honest by construction.
 */
class FriendsRepository private constructor(context: Context) {

    private val auth = AuthRepository.get(context)

    /** Finds people by handle. Never returns you. */
    suspend fun search(query: String): Result<List<Profile>> = call { token, me ->
        val term = URLEncoder.encode("*${query.trim().lowercase()}*", "UTF-8")
        val body = Supabase.select(
            "profiles",
            "handle=ilike.$term&select=id,handle,display_name&limit=20",
            token,
        )
        Supabase.json.decodeFromString(ListSerializer(Profile.serializer()), body)
            .filterNot { it.id == me }
    }

    suspend fun friends(): Result<List<Friend>> = call { token, me ->
        val ids = acceptedFriendIds(token, me)
        if (ids.isEmpty()) return@call emptyList()

        val list = ids.joinToString(",")
        val body = Supabase.select(
            "profiles",
            "id=in.($list)&select=id,handle,display_name,now_playing(title,artist,duration_ms,position_ms,is_playing,updated_at,source_track_id,source_package)",
            token,
        )
        Supabase.json
            .decodeFromString(ListSerializer(ProfileWithPlayback.serializer()), body)
            .map {
                Friend(
                    profile = Profile(it.id, it.handle, it.displayName),
                    // Absent when they are not playing, or not sharing with you.
                    nowPlaying = it.nowPlaying,
                )
            }
            .sortedByDescending { it.nowPlaying?.isPlaying == true }
    }

    /** What one person is playing, for following them. */
    suspend fun nowPlayingOf(userId: String): Result<RemoteNowPlaying?> = call { token, _ ->
        val body = Supabase.select(
            "now_playing",
            "user_id=eq.$userId&select=title,artist,duration_ms,position_ms,is_playing,updated_at," +
                "source_track_id,source_package",
            token,
        )
        Supabase.json
            .decodeFromString(ListSerializer(RemoteNowPlaying.serializer()), body)
            .firstOrNull()
    }

    suspend fun pending(): Result<List<PendingRequest>> = call { token, me ->
        val rows = friendshipRows(token, me).filter { it.status == "pending" }
        if (rows.isEmpty()) return@call emptyList()

        val others = rows.associateBy({ if (it.userA == me) it.userB else it.userA }, { it })
        val body = Supabase.select(
            "profiles",
            "id=in.(${others.keys.joinToString(",")})&select=id,handle,display_name",
            token,
        )
        Supabase.json.decodeFromString(ListSerializer(Profile.serializer()), body).map { profile ->
            PendingRequest(
                profile = profile,
                incoming = others[profile.id]?.requestedBy != me,
            )
        }
    }

    suspend fun request(other: Profile): Result<Unit> = call { token, me ->
        // The pair is stored in a fixed order, which is what stops two rows from
        // ever disagreeing about whether two people are friends.
        val rows = buildJsonArray {
            add(
                buildJsonObject {
                    put("user_a", minOf(me, other.id))
                    put("user_b", maxOf(me, other.id))
                    put("status", "pending")
                    put("requested_by", me)
                },
            )
        }
        Supabase.insert("friendships", rows, token, upsertOnConflict = "user_a,user_b")
    }

    suspend fun accept(other: Profile): Result<Unit> = call { token, me ->
        Supabase.patch(
            "friendships",
            "user_a=eq.${minOf(me, other.id)}&user_b=eq.${maxOf(me, other.id)}",
            buildJsonObject { put("status", "accepted") },
            token,
        )
    }

    suspend fun remove(other: Profile): Result<Unit> = call { token, me ->
        Supabase.delete(
            "friendships",
            "user_a=eq.${minOf(me, other.id)}&user_b=eq.${maxOf(me, other.id)}",
            token,
        )
    }

    private fun acceptedFriendIds(token: String, me: String): List<String> =
        friendshipRows(token, me)
            .filter { it.status == "accepted" }
            .map { if (it.userA == me) it.userB else it.userA }

    private fun friendshipRows(token: String, me: String): List<FriendshipRow> {
        val body = Supabase.select(
            "friendships",
            "or=(user_a.eq.$me,user_b.eq.$me)&select=user_a,user_b,status,requested_by",
            token,
        )
        return Supabase.json.decodeFromString(ListSerializer(FriendshipRow.serializer()), body)
    }

    private suspend fun <T> call(block: suspend (token: String, me: String) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                val me = auth.session.value?.userId
                    ?: error("Sign in to use friends.")
                val token = auth.validAccessToken()
                    ?: error("Your session expired. Sign in again.")
                block(token, me)
            }
        }

    companion object {
        @Volatile private var instance: FriendsRepository? = null

        fun get(context: Context): FriendsRepository =
            instance ?: synchronized(this) {
                instance ?: FriendsRepository(context.applicationContext).also { instance = it }
            }
    }
}
