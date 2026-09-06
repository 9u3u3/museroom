package com.museroom.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museroom.app.data.LibrarySongEntity
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.data.PlaylistEntity
import com.museroom.app.data.PlaylistSongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library, exercised against a real SQLite rather than a mock.
 *
 * These are the writes nobody notices going wrong. A like that does not stick
 * or a playlist that quietly fails to save is not a crash, it is somebody
 * doing the same thing twice and wondering why.
 */
@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {

    private lateinit var db: MuseroomDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MuseroomDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private fun song(id: String, title: String = "A song") = LibrarySongEntity(
        id = id, title = title, artist = "Somebody", album = "A record",
        durationMs = 200_000, cover = "https://example/$id",
    )

    @Test
    fun playingASongTwiceCountsItTwiceAndKeepsTheLike() = runBlocking {
        val library = db.library()
        library.played("a", "Nights", "Frank Ocean", "Blonde", 307_000, "cover", 1_000)
        library.setLiked("a", true, 2_000)
        library.played("a", "Nights", "Frank Ocean", "Blonde", 307_000, "cover", 3_000)

        val row = library.song("a")!!
        assertEquals(2, row.plays)
        assertEquals(3_000, row.playedAt)
        // The whole point of writing a play as one statement: a like set
        // between two plays has to survive the second one.
        assertTrue(row.liked)
    }

    @Test
    fun aPlayNeverBlanksWhatItDoesNotKnow() = runBlocking {
        val library = db.library()
        library.played("a", "Nights", "Frank Ocean", "Blonde", 307_000, "cover", 1_000)
        // A later play from a place that knows no album must not erase the one
        // we already had.
        library.played("a", "Nights", "Frank Ocean", "", 0, "", 2_000)

        val row = library.song("a")!!
        assertEquals("Blonde", row.album)
        assertEquals(307_000, row.durationMs)
        assertEquals("cover", row.cover)
    }

    @Test
    fun likedSongsComeBackNewestFirst() = runBlocking {
        val library = db.library()
        library.put(song("a"))
        library.put(song("b"))
        library.setLiked("a", true, 1_000)
        library.setLiked("b", true, 2_000)

        assertEquals(listOf("b", "a"), library.liked().first().map { it.id })
        assertEquals(2, library.likedCount().first())
    }

    @Test
    fun aPlaylistKeepsTheOrderItWasGiven() = runBlocking {
        val library = db.library()
        val lists = db.playlists()
        listOf("a", "b", "c").forEach { library.put(song(it, "Song $it")) }

        val id = lists.create(PlaylistEntity(name = "Late drives", createdAt = 1))
        listOf("c", "a", "b").forEach {
            lists.add(PlaylistSongEntity(id, it, lists.nextPosition(id)))
        }

        assertEquals(listOf("c", "a", "b"), lists.songsIn(id).first().map { it.id })

        lists.remove(id, "a")
        assertEquals(listOf("c", "b"), lists.songsIn(id).first().map { it.id })
    }

    @Test
    fun aListSummaryCountsItsSongsAndShowsTheNewestCover() = runBlocking {
        val library = db.library()
        val lists = db.playlists()
        library.put(song("a"))
        library.put(song("b"))
        val id = lists.create(PlaylistEntity(name = "Late drives", createdAt = 1))
        lists.add(PlaylistSongEntity(id, "a", 0))
        lists.add(PlaylistSongEntity(id, "b", 1))

        val summary = lists.summaries().first().single()
        assertEquals("Late drives", summary.name)
        assertEquals(2, summary.songs)
        assertEquals("https://example/b", summary.cover)
    }

    @Test
    fun addingTheSameSongTwiceDoesNotDoubleIt() = runBlocking {
        val library = db.library()
        val lists = db.playlists()
        library.put(song("a"))
        val id = lists.create(PlaylistEntity(name = "Late drives", createdAt = 1))
        lists.add(PlaylistSongEntity(id, "a", 0))
        lists.add(PlaylistSongEntity(id, "a", 1))

        assertEquals(1, lists.songsIn(id).first().size)
    }

    @Test
    fun deletingAListTakesItsEntriesWithIt() = runBlocking {
        val library = db.library()
        val lists = db.playlists()
        library.put(song("a"))
        val id = lists.create(PlaylistEntity(name = "Late drives", createdAt = 1))
        lists.add(PlaylistSongEntity(id, "a", 0))

        lists.empty(id)
        lists.delete(id)

        assertTrue(lists.summaries().first().isEmpty())
        assertNull(lists.playlist(id).first())
        // The song itself is not somebody's to throw away just because a list
        // it was in has gone.
        assertEquals(1, library.count().first())
    }
}
