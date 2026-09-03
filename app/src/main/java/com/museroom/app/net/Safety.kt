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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class BlockRow(
    val blocked: String = "",
    val profiles: Who? = null,
) {
    @Serializable
    data class Who(
        val id: String = "",
        val handle: String = "",
        @SerialName("avatar_url") val avatarUrl: String? = null,
    )
}

/** Somebody you have blocked, as they need to appear on the list to undo it. */
data class BlockedPerson(val userId: String, val handle: String, val avatarUrl: String?)

/**
 * Blocking and reporting.
 *
 * A leaderboard shows a username and a picture to everyone signed in, and a
 * room can be walked into by somebody you have never met. Both of those are
 * fine right up until one person is unpleasant, at which point "ignore them"
 * is not an answer. Blocking is enforced by the database rather than by the
 * screen: hidden in the app still leaves every row readable by anybody who
 * asks the API directly.
 */
class SafetyRepository private constructor(context: Context) {

    private val auth = AuthRepository.get(context)

    private val _blocked = MutableStateFlow<List<BlockedPerson>>(emptyList())
    val blocked: StateFlow<List<BlockedPerson>> = _blocked.asStateFlow()

    /** The ids alone, for deciding what not to draw. */
    val blockedIds: Set<String> get() = _blocked.value.map { it.userId }.toSet()

    suspend fun refresh(): Result<List<BlockedPerson>> = io { token, _ ->
        val body = Supabase.select(
            "blocks",
            "select=blocked,profiles!blocks_blocked_fkey(id,handle,avatar_url)" +
                "&order=created_at.desc&limit=200",
            token,
        )
        Supabase.json.decodeFromString(ListSerializer(BlockRow.serializer()), body)
            .mapNotNull { row ->
                row.profiles?.let {
                    BlockedPerson(row.blocked, it.handle, it.avatarUrl)
                }
            }
            .also { _blocked.value = it }
    }

    /**
     * Blocks, and pulls apart whatever already connected you.
     *
     * Done in one database call rather than three from here, so a block cannot
     * half-apply: leaving the friendship standing would mean they still appear
     * on your list and can still see you under a friends-only setting.
     */
    suspend fun block(userId: String): Result<Unit> = io { token, _ ->
        Supabase.rpc("block_user", buildJsonObject { put("target", userId) }, token)
        refresh()
        Unit
    }

    suspend fun unblock(userId: String): Result<Unit> = io { token, me ->
        Supabase.delete("blocks", "blocker=eq.$me&blocked=eq.$userId", token)
        refresh()
        Unit
    }

    /**
     * Files a report. Nothing happens automatically, and it says so on screen:
     * a report that quietly did nothing while implying otherwise would be
     * worse than no report at all.
     */
    suspend fun report(userId: String, reason: String): Result<Unit> = io { token, me ->
        val rows = buildJsonArray {
            add(
                buildJsonObject {
                    put("reporter", me)
                    put("reported", userId)
                    put("reason", reason.take(500))
                },
            )
        }
        Supabase.insert("reports", rows, token)
    }

    /** Everything, gone, including the account itself. */
    suspend fun deleteAccount(): Result<Unit> = io { token, _ ->
        Supabase.rpc("delete_my_account", buildJsonObject { }, token)
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
        @Volatile private var instance: SafetyRepository? = null
        fun get(context: Context): SafetyRepository =
            instance ?: synchronized(this) {
                instance ?: SafetyRepository(context.applicationContext).also { instance = it }
            }
    }
}
