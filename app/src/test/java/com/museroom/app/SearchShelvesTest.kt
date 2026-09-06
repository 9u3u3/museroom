package com.museroom.app

import com.museroom.app.player.InnerTube
import com.museroom.app.player.reordered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sorting an unfiltered search into shelves, and moving a row in a list.
 *
 * The search half matters because the classification is done by what a row
 * carries rather than by the heading above it, and the headings are the only
 * part anybody would think to look at. If YouTube renames a shelf nothing here
 * changes; if it stops putting a page type on an endpoint, this fails, which is
 * the point.
 *
 * The fixture is written out rather than captured, because what is being pinned
 * is four shapes appearing together and a real response is fifty of one shape.
 */
class SearchShelvesTest {

    private fun row(columns: List<String>, endpoint: String): String =
        """
        {"musicResponsiveListItemRenderer":{
          "flexColumns":[${columns.joinToString(",") { text(it) }}],
          $endpoint,
          "thumbnail":{"thumbnails":[{"url":"https://i/x=w60-h60","width":60,"height":60}]}
        }}
        """.trimIndent()

    private fun text(value: String) =
        """{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$value"}]}}}"""

    private fun browse(id: String, pageType: String) =
        """"navigationEndpoint":{"browseEndpoint":{"browseId":"$id",
        "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":
        {"pageType":"MUSIC_PAGE_TYPE_$pageType"}}}}""".trimIndent()

    private val payload = """
        {"contents":{"rows":[
          ${row(listOf("Nights", "Song • Frank Ocean • Blonde • 5:07"), """"videoId":"abc123"""")},
          ${row(listOf("Nikes", "Video • Frank Ocean • 12M views • 5:14"), """"videoId":"vid999"""")},
          ${row(listOf("Blonde", "Album • Frank Ocean • 2016"), browse("MPREb_1", "ALBUM"))},
          ${row(listOf("Frank Ocean", "Artist • 4.1M subscribers"), browse("UC_frank", "ARTIST"))},
          ${row(listOf("Late drives", "Playlist • 41 tracks"), browse("VL_pl1", "PLAYLIST"))}
        ]}}
    """.trimIndent()

    @Test
    fun `a row goes to the shelf its own endpoint says it belongs to`() {
        val found = InnerTube.everything(payload)

        assertEquals(listOf("Nights"), found.songs.map { it.title })
        assertEquals(listOf("Nikes"), found.videos.map { it.title })
        assertEquals(listOf("MPREb_1"), found.albums.map { it.browseId })
        assertEquals(listOf("UC_frank"), found.artists.map { it.browseId })
        assertEquals(listOf("VL_pl1"), found.playlists.map { it.browseId })
    }

    /**
     * The line starts with what the row is, and dropping that word is the
     * difference between an artist called Frank Ocean and one called Song.
     */
    @Test
    fun `a song keeps enough of its line to be played and named`() {
        val song = InnerTube.everything(payload).songs.single()
        assertEquals("abc123", song.id)
        assertEquals("Frank Ocean", song.artist)
        assertEquals("Blonde", song.album)
        assertEquals(307_000, song.durationMs)
    }

    /**
     * A row saying what it is, or a view count where an album should be. The
     * two are different recordings of the same name at different lengths, and
     * a room where everybody has a different length spends its life correcting.
     */
    @Test
    fun `a view count is what makes something a video rather than a song`() {
        val found = InnerTube.everything(payload)
        assertTrue(found.songs.none { it.id == "vid999" })
        assertEquals("vid999", found.videos.single().id)
    }

    /**
     * The same information arrives as one column on some rows and as three on
     * others, and reading only the second column is how a song came to be by
     * an artist called "3:22".
     */
    @Test
    fun `a byline split across columns reads the same as one packed into one`() {
        val split = """
            {"contents":[
              ${row(listOf("Lucky", "Song", "Radiohead", "OK Computer", "4:23"), """"videoId":"lucky1"""")},
              ${row(listOf("Bones", "Song", "Radiohead"), """"videoId":"bones1"""")},
              ${row(listOf("Blow Out", "Song", "2:31"), """"videoId":"blow01"""")}
            ]}
        """.trimIndent()
        val songs = InnerTube.everything(split).songs.associateBy { it.id }

        assertEquals("Radiohead", songs.getValue("lucky1").artist)
        assertEquals("OK Computer", songs.getValue("lucky1").album)
        assertEquals(263_000, songs.getValue("lucky1").durationMs)

        // One word after the label is who made it, never what it is on.
        assertEquals("Radiohead", songs.getValue("bones1").artist)
        assertEquals("", songs.getValue("bones1").album)

        // A length and nothing else leaves the artist blank rather than
        // printing the time twice.
        assertEquals("", songs.getValue("blow01").artist)
        assertEquals(151_000, songs.getValue("blow01").durationMs)
    }

    /**
     * The rows under an artist's top-result card leave the artist's name out,
     * because the card immediately above has just said it. Read on their own
     * they are a length and a play count and nothing else.
     */
    @Test
    fun `a song under an artist card is credited to that artist`() {
        val card = """
            {"contents":[
              {"musicCardShelfRenderer":{
                "title":{"runs":[{"text":"Radiohead"}]},
                "subtitle":{"runs":[{"text":"Artist • 219M monthly audience"}]},
                "thumbnail":{"thumbnails":[{"url":"https://i/a=w60-h60","width":60,"height":60}]},
                ${browse("UC_radio", "ARTIST")}
              }},
              ${row(listOf("Let Down", "Song", "5:00", "202M plays"), """"videoId":"letdwn"""")}
            ]}
        """.trimIndent()

        val found = InnerTube.everything(card)
        assertEquals("Artist", found.top?.kind)
        val song = found.songs.single { it.id == "letdwn" }
        assertEquals("Radiohead", song.artist)
        assertEquals(300_000, song.durationMs)
        // A play count is not an album, and putting it where the album goes is
        // how a row comes to read "5:00 · 202M plays".
        assertEquals("", song.album)
    }

    @Test
    fun `one filtered search only fills the shelf it asked for`() {
        val albums = InnerTube.cards(payload, "ALBUM")
        assertEquals(listOf("Blonde"), albums.map { it.title })
        assertEquals("Album • Frank Ocean • 2016", albums.single().subtitle)
    }

    // --------------------------------------------------------- dragging a row --

    @Test
    fun `a row dragged down lands where it was dropped`() {
        assertEquals(listOf("b", "c", "a"), reordered(listOf("a", "b", "c"), 0, 2))
    }

    @Test
    fun `a row dragged up lands where it was dropped`() {
        assertEquals(listOf("c", "a", "b"), reordered(listOf("a", "b", "c"), 2, 0))
    }

    @Test
    fun `a move that changes nothing is refused rather than applied`() {
        assertNull(reordered(listOf("a", "b"), 1, 1))
        assertNull(reordered(listOf("a", "b"), 0, 5))
        assertNull(reordered(listOf("a", "b"), -1, 0))
        assertNull(reordered(emptyList<String>(), 0, 0))
    }
}
