package com.museroom.app

import com.museroom.app.player.InnerTube
import com.museroom.app.player.Streams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The player's half of the deal with YouTube, checked without a network.
 *
 * The fixture is a real answer with its signed URLs shortened. What is being
 * pinned is the shape of that answer, because the shape is the thing that
 * changes without telling anybody, and a green build is where we would want to
 * find out.
 */
class PlayerStreamsTest {

    private val payload: String =
        javaClass.classLoader!!.getResourceAsStream("player-answer.json")!!
            .bufferedReader().use { it.readText() }

    private fun answer() = InnerTube.parse("dQw4w9WgXcQ", payload, "ANDROID_VR")

    @Before
    fun clearCache() = Streams.forgetEverything()

    @Test
    fun `only audio formats survive parsing`() {
        val formats = answer().formats
        assertEquals(4, formats.size)
        assertTrue(formats.all { it.mimeType.startsWith("audio") })
    }

    @Test
    fun `a format whose signature is still scrambled is refused`() {
        // It is in the fixture, and it must not be in the answer: handing a
        // ciphered URL to the player fails later and looks like a dead track.
        assertTrue(answer().formats.none { it.itag == 141 })
    }

    @Test
    fun `the answer carries what the player needs to place a track`() {
        val a = answer()
        assertEquals("dQw4w9WgXcQ", a.videoId)
        assertEquals(213_000L, a.durationMs)
        assertTrue(a.expiresInSeconds > 0)
        val opus = a.formats.first { it.itag == 251 }
        assertTrue(opus.isWebm)
        assertTrue(opus.contentLength > 0)
    }

    @Test
    fun `a refusal is raised rather than returned as an empty answer`() {
        val refused = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}"""
        val thrown = runCatching { InnerTube.parse("x", refused, "IOS") }.exceptionOrNull()
        assertTrue(thrown is InnerTube.Unplayable)
        assertEquals("LOGIN_REQUIRED", (thrown as InnerTube.Unplayable).status)
    }

    @Test
    fun `quality picks the best format under its ceiling`() {
        val formats = answer().formats
        assertEquals(251, Streams.pick(formats, Streams.Quality.Max)!!.itag)
        assertEquals(251, Streams.pick(formats, Streams.Quality.High)!!.itag)
        // Low has to come in under 72k, which rules out both of the good ones.
        assertEquals(139, Streams.pick(formats, Streams.Quality.Low)!!.itag)
    }

    @Test
    fun `a ceiling nothing fits still returns the quietest format`() {
        // Better to play a track louder than asked than to refuse to play it.
        val only = answer().formats.filter { it.itag == 251 }
        assertEquals(251, Streams.pick(only, Streams.Quality.Low)!!.itag)
        assertNull(Streams.pick(emptyList(), Streams.Quality.High))
    }

    // ------------------------------------------------------------- the cache --

    private fun stream(id: String, expiresAtMs: Long) = Streams.Stream(
        videoId = id, url = "https://example/$id", headers = emptyMap(), itag = 251,
        mimeType = "audio/webm", bitrate = 136_544, contentLength = 3_433_755,
        durationMs = 213_061, loudnessDb = null, client = "ANDROID_VR",
        expiresAtMs = expiresAtMs,
    )

    @Test
    fun `a stream is remembered until it expires`() {
        Streams.remember(stream("a", expiresAtMs = 1_000))
        assertNotNull(Streams.cached("a", nowMs = 999))
        assertNull(Streams.cached("a", nowMs = 1_000))
    }

    @Test
    fun `an expired stream is not handed out again`() {
        Streams.remember(stream("a", expiresAtMs = 1_000))
        Streams.cached("a", nowMs = 5_000)
        assertNull(Streams.cached("a", nowMs = 0))
    }

    @Test
    fun `an answer that arrives after its id was invalidated loses`() {
        // A resolve starts, the stream it was working on is rejected, a second
        // resolve stores a good URL, and only then does the first one return.
        val generation = Streams.generation("a")
        Streams.forget("a")
        Streams.remember(stream("a", expiresAtMs = 9_000))
        assertTrue(Streams.cached("a", nowMs = 0)!!.expiresAtMs == 9_000L)

        val stale = Streams.remember(stream("a", expiresAtMs = 1), expectedGeneration = generation)
        assertTrue("a stale answer must not overwrite a fresh one", !stale)
        assertEquals(9_000L, Streams.cached("a", nowMs = 0)!!.expiresAtMs)
    }
}
