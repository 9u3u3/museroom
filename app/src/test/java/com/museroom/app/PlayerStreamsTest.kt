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
 * Search parsing and the stream cache, checked without a network.
 *
 * The fixture is a real search response, trimmed. What is being pinned is its
 * shape, because the shape is the thing that changes without telling anybody,
 * and a green build is where we would want to find out.
 *
 * Getting a stream address is not tested here and cannot usefully be: it is the
 * extraction library's job, it needs a bot token minted in a WebView, and the
 * only honest test of it is a phone playing a song to the end.
 */
class PlayerStreamsTest {

    @Before
    fun clearCache() = Streams.forgetEverything()

    // ------------------------------------------------------------- searching --

    private val songs: String =
        javaClass.classLoader!!.getResourceAsStream("search-songs.json")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `a search result carries enough to play and to name it`() {
        val results = InnerTube.results(songs)
        assertEquals(3, results.size)
        val first = results.first()
        assertEquals("LUjGtyYEi90", first.id)
        assertEquals("Weird Fishes / Arpeggi", first.title)
        assertEquals("Radiohead", first.artist)
        assertEquals("In Rainbows", first.album)
        assertEquals(319_000L, first.durationMs)
    }

    @Test
    fun `a search that matched nothing is empty rather than a failure`() {
        assertTrue(InnerTube.results("""{"contents":{}}""").isEmpty())
        assertTrue(InnerTube.results("not json at all").isEmpty())
    }

    @Test
    fun `durations are read in both shapes and refused otherwise`() {
        assertEquals(249_000L, InnerTube.clock("4:09"))
        assertEquals(3_731_000L, InnerTube.clock("1:02:11"))
        assertEquals(0L, InnerTube.clock("In Rainbows"))
        assertEquals(0L, InnerTube.clock(""))
    }

    private val queue: String =
        javaClass.classLoader!!.getResourceAsStream("radio-queue.json")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `a radio queue reads as songs with artists and lengths`() {
        val songs = InnerTube.queued(queue)
        assertEquals(4, songs.size)
        val first = songs.first()
        assertEquals("nhys3nF4ZDU", first.id)
        assertEquals("Reflections", first.title)
        assertEquals("The Neighbourhood", first.artist)
        assertEquals(245_000L, first.durationMs)
    }

    @Test
    fun `a view count is not mistaken for an album`() {
        // The second line of a queue entry is the album on a song and a view
        // count on a video, and "4.3M views" is not a record.
        val songs = InnerTube.queued(queue)
        assertTrue(songs.none { it.album.contains("views") })
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
