package com.museroom.app

import com.museroom.app.net.Realtime
import com.museroom.app.net.Watch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join message, pinned to the shape a real server was observed to accept.
 *
 * This is the failure mode worth a test: send Realtime a join it does not
 * understand and it replies that everything is fine, then never sends a single
 * row. On a phone that is indistinguishable from a friend who stopped playing,
 * so it would be found weeks later by somebody wondering why following felt
 * slow again. Every field below was verified against the live service before
 * being written down here.
 */
class RealtimeJoinTest {

    private val host = "6f4d238f-5039-4e04-961d-a7bc526a7102"
    private val token = "a.jwt.value"

    private fun joinFor(topic: String, vararg watching: Watch) =
        Json.parseToJsonElement(Realtime.joinMessage(topic, watching.toList(), token)).jsonObject

    private val join = joinFor("now_playing:$host", Watch("now_playing", "user_id=eq.$host"))

    @Test
    fun `it is a phoenix join on its own topic`() {
        assertEquals("phx_join", join["event"]?.jsonPrimitive?.content)
        assertEquals("realtime:now_playing:$host", join["topic"]?.jsonPrimitive?.content)
    }

    @Test
    fun `it subscribes to that one person's row and nobody else's`() {
        val change = join["payload"]!!.jsonObject["config"]!!.jsonObject["postgres_changes"]!!
            .jsonArray.single().jsonObject

        assertEquals("*", change["event"]?.jsonPrimitive?.content)
        assertEquals("public", change["schema"]?.jsonPrimitive?.content)
        assertEquals("now_playing", change["table"]?.jsonPrimitive?.content)
        // Without the filter this becomes a subscription to everybody's
        // listening, which is both a firehose and a thing we should not ask for.
        assertEquals("user_id=eq.$host", change["filter"]?.jsonPrimitive?.content)
    }

    @Test
    fun `it carries the user's token, not the anonymous key`() {
        // Realtime applies row-level security to what it forwards. Joining
        // without a user token is accepted and then silently delivers nothing,
        // which is exactly how this was first got wrong.
        assertEquals(
            token,
            join["payload"]?.jsonObject?.get("access_token")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `each join carries a fresh reference`() {
        val watch = Watch("now_playing", "user_id=eq.$host")
        val first = joinFor("now_playing:$host", watch)["ref"]!!.jsonPrimitive.content.toInt()
        val second = joinFor("now_playing:$host", watch)["ref"]!!.jsonPrimitive.content.toInt()
        assertTrue("refs must advance, got $first then $second", second > first)
    }

    /**
     * The same message, for a table that is not now_playing.
     *
     * Following somebody was the only thing pushed until requests were, and a
     * join builder that had one table's name written into it would have been
     * copied rather than reused. Each subscription needs its own topic, or the
     * second join lands on the first one's channel and replaces it.
     */
    @Test
    fun `it builds a join for any table and filter`() {
        val inbox = joinFor("listen_requests:$host", Watch("listen_requests", "to_user=eq.$host"))
        val change = inbox["payload"]!!.jsonObject["config"]!!.jsonObject["postgres_changes"]!!
            .jsonArray.single().jsonObject

        assertEquals("realtime:listen_requests:$host", inbox["topic"]?.jsonPrimitive?.content)
        assertEquals("listen_requests", change["table"]?.jsonPrimitive?.content)
        assertEquals("to_user=eq.$host", change["filter"]?.jsonPrimitive?.content)
        assertEquals("public", change["schema"]?.jsonPrimitive?.content)
    }

    /**
     * A friendship is stored as an ordered pair, so which column holds you
     * depends on how your id sorts against theirs. One equality filter is all
     * Realtime takes, so both sides need their own subscription — and they
     * belong on the same channel, because three idle sockets on a phone is
     * three connections, three threads and three heartbeats for one dot.
     */
    @Test
    fun `several tables ride on one channel`() {
        val requests = joinFor(
            "requests:$host",
            Watch("listen_requests", "to_user=eq.$host"),
            Watch("friendships", "user_a=eq.$host"),
            Watch("friendships", "user_b=eq.$host"),
        )
        val changes = requests["payload"]!!.jsonObject["config"]!!.jsonObject["postgres_changes"]!!
            .jsonArray.map { it.jsonObject }

        assertEquals(3, changes.size)
        assertEquals("realtime:requests:$host", requests["topic"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("listen_requests", "friendships", "friendships"),
            changes.map { it["table"]?.jsonPrimitive?.content },
        )
        assertEquals(
            listOf("to_user=eq.$host", "user_a=eq.$host", "user_b=eq.$host"),
            changes.map { it["filter"]?.jsonPrimitive?.content },
        )
        // Every one of them still has to be told which schema, or the service
        // accepts the join and sends nothing at all.
        assertTrue(changes.all { it["schema"]?.jsonPrimitive?.content == "public" })
    }
}
