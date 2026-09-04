package com.museroom.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

@Serializable
data class Profile(
    val id: String,
    val handle: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("join_mode") val joinMode: String = "ask",
) {
    val openToAll: Boolean get() = joinMode == "open"
}

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
    /**
     * An advert is on at their end. Not the same as having stopped: the track
     * is coming back, so a room holds rather than letting go.
     */
    @SerialName("is_advert") val isAdvert: Boolean = false,
    /**
     * The moment, in the database's clock, that everybody starts this track.
     *
     * Blank in the ordinary case. Present only when the host held their own
     * music so the room could begin together, which is worth doing solely
     * when there is a room.
     */
    @SerialName("starts_at") val startsAt: String? = null,
    /** Where that start begins, which is where the host's player was stopped. */
    @SerialName("start_position_ms") val startPositionMs: Long = 0,
) {
    /**
     * Whether this is somebody listening now or the last thing they played.
     *
     * A row is only rewritten when something changes, so a phone that went
     * quiet without saying so leaves its final second on the screen forever.
     * A friend frozen at 2:15 of a 2:15 track is not listening to anything.
     */
    val isLive: Boolean
        get() {
            if (!isPlaying || title.isBlank()) return false
            val takenAt = runCatching { java.time.Instant.parse(updatedAt).toEpochMilli() }
                .getOrNull() ?: return isPlaying
            val age = System.currentTimeMillis() - takenAt
            if (age > STALE_AFTER_MS) return false
            // Past the end of the track, with nothing said since, means the
            // song finished and the report never caught up.
            return durationMs <= 0 || positionMs + age < durationMs + 15_000
        }
}

/** Long enough for a slow publish, short enough that a dead phone drops off. */
private const val STALE_AFTER_MS = 90_000L

@Serializable
private data class ProfileWithPlayback(
    val id: String,
    val handle: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("join_mode") val joinMode: String = "ask",
    // PostgREST embeds this as a single object, not an array: now_playing's
    // primary key is also its foreign key, so the relationship is one-to-one.
    @SerialName("now_playing") val nowPlaying: RemoteNowPlaying? = null,
)

/** Somebody listening along with you. */
data class RoomMember(
    val userId: String,
    val handle: String,
    val avatarUrl: String? = null,
    /**
     * How late they were for the last start they were given, in milliseconds.
     * Negative means they were ready with time to spare. Null means they have
     * not been given one yet.
     */
    val lateMs: Int? = null,
    /**
     * The track they have fetched and are holding, silent, waiting for the
     * agreed moment. Null when they are not waiting for anything.
     */
    val readyFor: String? = null,
)

@Serializable
private data class RosterRow(
    @SerialName("user_id") val userId: String = "",
    val handle: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("late_ms") val lateMs: Int? = null,
    @SerialName("ready_for") val readyFor: String? = null,
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
 * What came of asking to be friends.
 *
 * The server decides, and it decides by looking rather than by being told, so
 * asking twice is answered rather than obeyed. Every one of these except [Sent]
 * means nothing was written.
 */
enum class RequestOutcome {
    Sent,
    AlreadyFriends,
    AlreadyRequested,

    /** They asked you first. The answer to this is Accept, not another request. */
    TheyAskedYou,
    Blocked,
    Self,
}

/**
 * Where you stand with somebody, as far as a button needs to know.
 *
 * Read before the button is drawn rather than after it is pressed. A search
 * result that offers "Add" to somebody you are already friends with is the
 * whole bug this exists to close.
 */
enum class RelationState {
    Stranger,
    Friends,
    PendingByMe,
    PendingByThem,
}

/**
 * What one friendship row means to one of the two people in it.
 *
 * Null for a row that means nothing to a button: a status nobody asked about.
 */
internal fun relationOf(status: String, requestedBy: String, me: String): RelationState? = when {
    status == "accepted" -> RelationState.Friends
    status != "pending" -> null
    requestedBy == me -> RelationState.PendingByMe
    else -> RelationState.PendingByThem
}

/** The server's word for what it did, as something the app can act on. */
internal fun outcomeOf(raw: String): RequestOutcome = when (raw.trim().trim('"')) {
    "sent" -> RequestOutcome.Sent
    "already_friends" -> RequestOutcome.AlreadyFriends
    "already_requested" -> RequestOutcome.AlreadyRequested
    "they_asked_you" -> RequestOutcome.TheyAskedYou
    "blocked" -> RequestOutcome.Blocked
    "self" -> RequestOutcome.Self
    else -> error("The server answered a friend request with \"$raw\".")
}

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
            "handle=ilike.$term&select=id,handle,display_name,avatar_url,join_mode&limit=20",
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
            "id=in.($list)&select=id,handle,display_name,avatar_url,join_mode,now_playing(title,artist,duration_ms,position_ms,is_playing,updated_at,source_track_id,source_package,is_advert)",
            token,
        )
        Supabase.json
            .decodeFromString(ListSerializer(ProfileWithPlayback.serializer()), body)
            .map {
                Friend(
                    profile = Profile(it.id, it.handle, it.displayName, it.avatarUrl, it.joinMode),
                    // Absent when they are not playing, or not sharing with you,
                    // and dropped when it is the last thing they played rather
                    // than something they are playing.
                    nowPlaying = it.nowPlaying?.takeIf { np -> np.isLive },
                )
            }
            .sortedByDescending { it.nowPlaying != null }
    }

    /** What one person is playing, for following them. */
    suspend fun nowPlayingOf(userId: String): Result<RemoteNowPlaying?> = call { token, _ ->
        val body = Supabase.select(
            "now_playing",
            "user_id=eq.$userId&select=title,artist,duration_ms,position_ms,is_playing,updated_at," +
                "source_track_id,source_package,is_advert,starts_at,start_position_ms",
            token,
        )
        Supabase.json
            .decodeFromString(ListSerializer(RemoteNowPlaying.serializer()), body)
            .firstOrNull()
    }

    /**
     * Everybody in a room, including you, and including people whose own rows
     * are not yours to read.
     *
     * Through a function rather than by selecting the rows. A stranger who
     * walked in from Nearby shares nothing with you, so their row is closed to
     * you — and they are still in the room, which is the one thing the roster
     * has to be right about. Being present is all that comes back; not a note
     * of what any of them is playing.
     */
    suspend fun roomMembersOf(hostId: String): Result<List<RoomMember>> = call { token, _ ->
        val body = Supabase.rpc("room_members", buildJsonObject { put("host", hostId) }, token)
        Supabase.json.decodeFromString(ListSerializer(RosterRow.serializer()), body)
            .map { RoomMember(it.userId, it.handle, it.avatarUrl, it.lateMs, it.readyFor) }
            .filter { it.handle.isNotBlank() }
    }

    suspend fun pending(): Result<List<PendingRequest>> = call { token, me ->
        val rows = friendshipRows(token, me).filter { it.status == "pending" }
        if (rows.isEmpty()) return@call emptyList()

        val others = rows.associateBy({ if (it.userA == me) it.userB else it.userA }, { it })
        val body = Supabase.select(
            "profiles",
            "id=in.(${others.keys.joinToString(",")})&select=id,handle,display_name,avatar_url,join_mode",
            token,
        )
        Supabase.json.decodeFromString(ListSerializer(Profile.serializer()), body).map { profile ->
            PendingRequest(
                profile = profile,
                incoming = others[profile.id]?.requestedBy != me,
            )
        }
    }

    /**
     * Ask to be friends, and be told what actually happened.
     *
     * Through a function rather than by writing the row. The pair is stored in
     * one fixed order under one primary key, so an upsert from the client was
     * not a second request at all — it overwrote whatever was there, which
     * turned "add a friend you already have" into "quietly demote a friendship
     * back to pending" for both people. The client no longer has the reach to
     * do that; see the migration.
     */
    suspend fun request(other: Profile): Result<RequestOutcome> = call { token, _ ->
        val body = buildJsonObject { put("target", other.id) }
        // A function returning a scalar comes back as a bare JSON string.
        outcomeOf(Supabase.rpc("request_friendship", body, token))
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

    /**
     * Where you stand with each of these people.
     *
     * Every row involving you comes back in one request, which is the same
     * request [friends] and [pending] already make, so asking about twenty
     * search results costs no more than asking about one. Anybody with no row
     * is a stranger.
     *
     * Blocking is not represented here. A block deletes the friendship
     * outright, so a blocked person is indistinguishable from a stranger by
     * these rows alone, and the server says so plainly when the request is
     * actually made.
     */
    suspend fun relationships(otherIds: List<String>): Result<Map<String, RelationState>> =
        call { token, me ->
            if (otherIds.isEmpty()) return@call emptyMap()
            val wanted = otherIds.toSet()
            val known = friendshipRows(token, me)
                .mapNotNull { row ->
                    val other = if (row.userA == me) row.userB else row.userA
                    if (other !in wanted) return@mapNotNull null
                    val state = relationOf(row.status, row.requestedBy, me) ?: return@mapNotNull null
                    other to state
                }
                .toMap()
            wanted.associateWith { known[it] ?: RelationState.Stranger }
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
