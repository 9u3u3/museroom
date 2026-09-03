package com.museroom.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields

@Serializable
data class BoardEntry(
    val rank: Int,
    @SerialName("user_id") val userId: String,
    @SerialName("credited_ms") val creditedMs: Long,
    val trackCount: Long = 0,
    val likes: Long = 0,
    val handle: String = "",
    val avatarUrl: String? = null,
)

@Serializable
private data class BoardRow(
    val rank: Int,
    @SerialName("like_rank") val likeRank: Int = 0,
    @SerialName("user_id") val userId: String,
    @SerialName("credited_ms") val creditedMs: Long,
    @SerialName("track_count") val trackCount: Long = 0,
    val likes: Long = 0,
    val profiles: Who? = null,
) {
    @Serializable
    data class Who(
        val handle: String = "",
        @SerialName("avatar_url") val avatarUrl: String? = null,
    )
}

enum class BoardPeriod(val key: String) {
    Day("day"), Week("week"), Month("month"), All("all")
}

/**
 * Which of two questions the board is answering.
 *
 * Minutes reward whoever left something running longest, which is a measure of
 * stamina. Likes are the only number here other people decide, so they get an
 * order of their own rather than a column nobody sorts by.
 */
enum class BoardSort(val label: String, val column: String) {
    Minutes("Minutes", "rank"),
    Likes("Likes", "like_rank"),
}

/**
 * The leaderboard, read from the precomputed table rather than ranked on demand.
 *
 * Ranking every user on every open is the query that gets slower exactly as the
 * product succeeds, so a scheduled pass writes the ranks and this reads a page
 * of them. The refresh is nudged before reading so a board is never stale by
 * more than the last open; it is cheap and idempotent.
 */
class BoardRepository private constructor(context: Context) {

    private val auth = AuthRepository.get(context)
    private var lastRefresh = 0L

    suspend fun top(
        period: BoardPeriod,
        sort: BoardSort = BoardSort.Minutes,
        limit: Int = 100,
    ): Result<List<BoardEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = auth.validAccessToken() ?: error("Sign in to see the board.")
                nudgeRefresh(token)

                val body = Supabase.select(
                    "leaderboard_entries",
                    "period=eq.${period.key}&period_key=eq.${periodKey(period)}" +
                        "&select=rank,like_rank,user_id,credited_ms,track_count,likes," +
                        "profiles(handle,avatar_url)" +
                        "&order=${sort.column}.asc&limit=$limit",
                    token,
                )
                Supabase.json
                    .decodeFromString(ListSerializer(BoardRow.serializer()), body)
                    .map { it.toEntry(sort) }
            }
        }

    /**
     * Just your own row, for a glance somewhere small like the header. Reads
     * one row rather than a page of a hundred, and skips the refresh nudge:
     * whatever the last scheduled pass wrote is close enough for a header
     * badge, and it means this never blocks on a write the board screen
     * would otherwise pay for anyway.
     */
    suspend fun myRank(period: BoardPeriod): Result<BoardEntry?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = auth.validAccessToken() ?: return@runCatching null
                val me = auth.session.value?.userId ?: return@runCatching null
                val body = Supabase.select(
                    "leaderboard_entries",
                    "period=eq.${period.key}&period_key=eq.${periodKey(period)}&user_id=eq.$me" +
                        "&select=rank,like_rank,user_id,credited_ms,track_count,likes," +
                        "profiles(handle,avatar_url)&limit=1",
                    token,
                )
                Supabase.json
                    .decodeFromString(ListSerializer(BoardRow.serializer()), body)
                    .firstOrNull()
                    ?.toEntry(BoardSort.Minutes)
            }
        }

    /** Whichever of the two ranks the board is currently sorted by. */
    private fun BoardRow.toEntry(sort: BoardSort) = BoardEntry(
        rank = if (sort == BoardSort.Likes) likeRank else rank,
        userId = userId,
        creditedMs = creditedMs,
        trackCount = trackCount,
        likes = likes,
        handle = profiles?.handle.orEmpty(),
        avatarUrl = profiles?.avatarUrl,
    )

    private fun nudgeRefresh(token: String) {
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 60_000) return
        lastRefresh = now
        runCatching { Supabase.rpc("refresh_leaderboards", buildJsonObject { }, token) }
    }

    /** Must match the keys refresh_leaderboards writes, which are UTC. */
    private fun periodKey(period: BoardPeriod): String {
        val now = Instant.now().atZone(ZoneOffset.UTC)
        return when (period) {
            BoardPeriod.Day -> "%d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
            BoardPeriod.Week ->
                "%d-W%02d".format(
                    now.get(IsoFields.WEEK_BASED_YEAR),
                    now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                )
            BoardPeriod.Month -> "%d-%02d".format(now.year, now.monthValue)
            BoardPeriod.All -> "all"
        }
    }

    companion object {
        @Volatile private var instance: BoardRepository? = null
        fun get(context: Context): BoardRepository =
            instance ?: synchronized(this) {
                instance ?: BoardRepository(context.applicationContext).also { instance = it }
            }
    }
}
