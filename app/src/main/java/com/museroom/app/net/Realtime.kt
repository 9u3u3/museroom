package com.museroom.app.net

import android.util.Log
import com.museroom.app.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Being told when a row changes, rather than asking.
 *
 * Following somebody used to mean asking every two seconds whether they were
 * still on the same track — two seconds of not knowing before a skip is even
 * noticed, on top of however long the host took to write it down, against a
 * whole budget of about two seconds for feeling in step. A change to the one
 * row that has to be timely is worth being pushed.
 *
 * Written directly against the socket rather than by pulling in a Supabase
 * client library: this is one table, one filter, and one message shape, and
 * the protocol underneath is small enough that a dependency would be the
 * larger thing to reason about. Measured end to end from Singapore, a commit
 * reaches the phone in well under a second.
 */
/**
 * One table, filtered to the rows worth being told about.
 *
 * The filter is a single equality and the service takes no more than that, so
 * a condition with two halves — being either side of a stored pair, say — is
 * two of these rather than one cleverer one.
 */
data class Watch(val table: String, val filter: String)

object Realtime {

    private const val TAG = "MuseroomRealtime"
    private const val HEARTBEAT_MS = 25_000L

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val refs = AtomicInteger(0)

    /**
     * Every change to one person's now-playing row, for as long as this is
     * collected. Closing the flow closes the socket.
     */
    fun nowPlayingOf(userId: String, accessToken: String): Flow<RemoteNowPlaying> =
        changes(
            "now_playing:$userId",
            listOf(Watch("now_playing", "user_id=eq.$userId")),
            accessToken,
        )
            .mapNotNull { record ->
                runCatching {
                    Supabase.json.decodeFromJsonElement(RemoteNowPlaying.serializer(), record)
                }.getOrNull()
            }

    /**
     * The rows of one table that match one filter, pushed as they change.
     *
     * The socket is not a promise. If it never connects, or drops and cannot
     * come back, this simply emits nothing — callers are expected to keep a
     * slow poll running underneath rather than treat silence as "unchanged".
     *
     * What arrives is the row, and callers are not expected to trust it as the
     * new state of anything. For a single row being followed it happens to be
     * exactly that; for a list it is only the news that the list changed, and
     * re-reading through the normal query keeps row-level security and every
     * other filter in one place instead of two.
     *
     * Realtime takes one equality filter per subscription and no more, so
     * anything needing two conditions needs two [Watch]es. They belong on one
     * channel rather than one each: a socket costs a connection, a thread and
     * a heartbeat every twenty-five seconds, and three of those sitting idle
     * on a phone for the sake of one dot is not a trade worth making.
     */
    fun changes(
        topic: String,
        watching: List<Watch>,
        accessToken: String,
    ): Flow<JsonObject> = callbackFlow {
        val url = BuildConfig.SUPABASE_URL.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") +
            "/realtime/v1/websocket?apikey=${BuildConfig.SUPABASE_ANON_KEY}&vsn=1.0.0"

        var socket: WebSocket? = null
        var beating = true

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(joinMessage(topic, watching, accessToken))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching {
                    Supabase.json.parseToJsonElement(text).jsonObject
                }.getOrNull() ?: return

                if (message["event"]?.jsonPrimitive?.content != "postgres_changes") return
                val record = message["payload"]?.jsonObject
                    ?.get("data")?.jsonObject
                    ?.get("record") as? JsonObject ?: return

                trySend(record)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (BuildConfig.DEBUG) Log.d(TAG, "socket failed: ${t.message}")
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }

        socket = http.newWebSocket(Request.Builder().url(url).build(), listener)

        // Phoenix drops a connection it has not heard from. The library's own
        // ping frames are not the same thing as this heartbeat.
        val beat = Thread {
            while (beating) {
                Thread.sleep(HEARTBEAT_MS)
                if (!beating) break
                socket?.send(
                    Supabase.json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("topic", "phoenix")
                            put("event", "heartbeat")
                            put("payload", buildJsonObject { })
                            put("ref", refs.incrementAndGet().toString())
                        },
                    ),
                )
            }
        }.apply { isDaemon = true; start() }

        awaitClose {
            beating = false
            beat.interrupt()
            runCatching { socket?.close(1000, null) }
        }
    }

    /**
     * Internal rather than private so a test can pin the shape. The server
     * accepts a wrong join message and then sends nothing, which on a phone
     * looks exactly like a host who stopped playing.
     */
    internal fun joinMessage(
        topic: String,
        watching: List<Watch>,
        accessToken: String,
    ): String {
        val body = buildJsonObject {
            put("topic", "realtime:$topic")
            put("event", "phx_join")
            putJsonObject("payload") {
                putJsonObject("config") {
                    putJsonObject("broadcast") { put("self", false) }
                    putJsonObject("presence") { put("key", "") }
                    put(
                        "postgres_changes",
                        buildJsonArray {
                            watching.forEach { watch ->
                                add(
                                    buildJsonObject {
                                        put("event", "*")
                                        put("schema", "public")
                                        put("table", watch.table)
                                        put("filter", watch.filter)
                                    },
                                )
                            }
                        },
                    )
                }
                // Realtime applies row-level security to what it forwards, so
                // this is the difference between being sent the row and being
                // sent nothing at all.
                put("access_token", accessToken)
            }
            put("ref", refs.incrementAndGet().toString())
            put("join_ref", "1")
        }
        return Supabase.json.encodeToString(JsonObject.serializer(), body)
    }
}
