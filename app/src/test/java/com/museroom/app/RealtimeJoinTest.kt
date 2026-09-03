package com.museroom.app

import com.museroom.app.net.Realtime
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

    private val join = Json.parseToJsonElement(Realtime.joinMessage(host, token)).jsonObject

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
        val first = Json.parseToJsonElement(Realtime.joinMessage(host, token))
            .jsonObject["ref"]!!.jsonPrimitive.content.toInt()
        val second = Json.parseToJsonElement(Realtime.joinMessage(host, token))
            .jsonObject["ref"]!!.jsonPrimitive.content.toInt()
        assertTrue("refs must advance, got $first then $second", second > first)
    }
}
