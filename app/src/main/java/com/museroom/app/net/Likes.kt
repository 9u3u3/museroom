package com.museroom.app.net

import android.content.Context
import com.museroom.app.media.Fingerprint
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

@Serializable
private data class LikeRow(
    val liked: String = "",
    val fingerprint: String = "",
)

/** Somebody liked something you played. */
data class ReceivedLike(
    val handle: String,
    val title: String,
    val artist: String,
    val at: String,
)

@Serializable
private data class ReceivedRow(
    val title: String = "",
    val artist: String = "",
    @SerialName("created_at") val createdAt: String = "",
    // Two foreign keys point at profiles from this table, so the one meant
    // here has to be named or PostgREST refuses to guess.
    @SerialName("profiles!likes_liker_fkey") val liker: Who? = null,
) {
    @Serializable data class Who(val handle: String = "")
}

/**
 * Saying you like what somebody is playing.
 *
 * The one number here that other people decide. Minutes reward whoever leaves
 * something running longest, which is a measure of endurance rather than of
 * taste; a like is the other half of the board, and the reason to look at
 * somebody else's screen at all.
 *
 * Nothing about the track is sent. The server reads what the other person is
 * playing and records that, so a like always refers to something they were
 * really playing at the moment it was sent, and no client can invent one.
 */
class LikesRepository private constructor(context: Context) {

    private val auth = AuthRepository.get(context)

    /** "userId|fingerprint" for everything this phone has liked. */
    private val _mine = MutableStateFlow<Set<String>>(emptySet())
    val mine: StateFlow<Set<String>> = _mine.asStateFlow()

    fun key(userId: String, title: String, artist: String, durationMs: Long): String =
        "$userId|${Fingerprint.of(title, artist, durationMs)}"

    fun likes(userId: String, title: String, artist: String, durationMs: Long): Boolean =
        key(userId, title, artist, durationMs) in _mine.value

    /** Everything you have ever liked, so a heart is already filled when you look. */
    suspend fun refresh(): Result<Unit> = io { token, me ->
        val body = Supabase.select(
            "likes",
            "liker=eq.$me&select=liked,fingerprint&order=created_at.desc&limit=1000",
            token,
        )
        _mine.value = Supabase.json
            .decodeFromString(ListSerializer(LikeRow.serializer()), body)
            .map { "${it.liked}|${it.fingerprint}" }
            .toSet()
    }

    /**
     * Likes whatever they are playing. Returns false when there was nothing to
     * like — they stopped, they do not share with you, or you already had.
     */
    suspend fun like(
        userId: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): Result<Boolean> = io { token, _ ->
        val body = Supabase.rpc("like_track", buildJsonObject { put("target", userId) }, token)
        // Filled at once so the tap feels like it did something, then put
        // straight, because the server has the last word on whether the like
        // exists and a heart that lies is worse than one that flickers.
        _mine.value = _mine.value + key(userId, title, artist, durationMs)
        refresh()
        body.trim() == "true"
    }

    suspend fun unlike(
        userId: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): Result<Boolean> = io { token, _ ->
        val body = Supabase.rpc("unlike_track", buildJsonObject { put("target", userId) }, token)
        _mine.value = _mine.value - key(userId, title, artist, durationMs)
        refresh()
        body.trim() == "true"
    }

    /**
     * Likes that arrived after a moment you name.
     *
     * Getting one is the whole point of the feature, and a number that only
     * moves while somebody happens to have their own page open is not worth
     * having. Newest last, so the caller can take the final stamp as its next
     * watermark.
     */
    suspend fun received(after: String): Result<List<ReceivedLike>> = io { token, me ->
        val since = java.net.URLEncoder.encode(after, "UTF-8")
        val body = Supabase.select(
            "likes",
            "liked=eq.$me&created_at=gt.$since" +
                "&select=title,artist,created_at,profiles!likes_liker_fkey(handle)" +
                "&order=created_at.asc&limit=20",
            token,
        )
        Supabase.json
            .decodeFromString(ListSerializer(ReceivedRow.serializer()), body)
            .map { ReceivedLike(it.liker?.handle.orEmpty(), it.title, it.artist, it.createdAt) }
            .filter { it.handle.isNotBlank() }
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
        @Volatile private var instance: LikesRepository? = null
        fun get(context: Context): LikesRepository =
            instance ?: synchronized(this) {
                instance ?: LikesRepository(context.applicationContext).also { instance = it }
            }
    }
}

@Serializable
data class PublicProfile(
    val id: String = "",
    val handle: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("likes_received") val likesReceived: Int = 0,
    @SerialName("on_global_board") val onGlobalBoard: Boolean = true,
    @SerialName("join_mode") val joinMode: String = "ask",
    /** Null when they have opted out of the board, which hides their totals. */
    @SerialName("credited_ms") val creditedMs: Long? = null,
    @SerialName("track_count") val trackCount: Long? = null,
    val rank: Int? = null,
    @SerialName("like_rank") val likeRank: Int? = null,
    @SerialName("is_friend") val isFriend: Boolean = false,
    @SerialName("shares_with_me") val sharesWithMe: Boolean = false,
    @SerialName("likes_from_me") val likesFromMe: Long = 0,
) {
    val openToAll: Boolean get() = joinMode == "open"
}

/**
 * Somebody else's page.
 *
 * One call rather than five, and a security-definer one, so the decision about
 * what a stranger may see is made once in the database instead of assembled
 * out of whatever the client happened to ask for. Listening history is not in
 * it: the privacy policy says history is yours alone, and a convenient screen
 * is not a reason to go back on that.
 */
class PeopleRepository private constructor(context: Context) {

    private val auth = AuthRepository.get(context)

    suspend fun profile(userId: String): Result<PublicProfile?> = withContext(Dispatchers.IO) {
        runCatching {
            val token = auth.validAccessToken() ?: error("Sign in first.")
            val body = Supabase.rpc("public_profile", buildJsonObject { put("target", userId) }, token)
            Supabase.json
                .decodeFromString(ListSerializer(PublicProfile.serializer()), body)
                .firstOrNull()
        }
    }

    companion object {
        @Volatile private var instance: PeopleRepository? = null
        fun get(context: Context): PeopleRepository =
            instance ?: synchronized(this) {
                instance ?: PeopleRepository(context.applicationContext).also { instance = it }
            }
    }
}
