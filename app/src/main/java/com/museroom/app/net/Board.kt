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
    val handle: String = "",
)

@Serializable
private data class BoardRow(
    val rank: Int,
    @SerialName("user_id") val userId: String,
    @SerialName("credited_ms") val creditedMs: Long,
    val profiles: Handle? = null,
) {
    @Serializable data class Handle(val handle: String = "")
}

enum class BoardPeriod(val key: String) { Week("week"), Month("month"), All("all") }

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

    suspend fun top(period: BoardPeriod, limit: Int = 100): Result<List<BoardEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = auth.validAccessToken() ?: error("Sign in to see the board.")
                nudgeRefresh(token)

                val body = Supabase.select(
                    "leaderboard_entries",
                    "period=eq.${period.key}&period_key=eq.${periodKey(period)}" +
                        "&select=rank,user_id,credited_ms,profiles(handle)" +
                        "&order=rank.asc&limit=$limit",
                    token,
                )
                Supabase.json
                    .decodeFromString(ListSerializer(BoardRow.serializer()), body)
                    .map { BoardEntry(it.rank, it.userId, it.creditedMs, it.profiles?.handle.orEmpty()) }
            }
        }

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
