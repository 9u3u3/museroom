package com.museroom.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museroom.app.data.MuseroomDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrading somebody's library rather than replacing it.
 *
 * This is the test that exists because of what it would cost to be wrong. The
 * migrations here are written by hand, and a hand-written one that produces a
 * table Room does not recognise throws on open — not on a fresh install, where
 * everything is built from the current schema and nothing is checked against
 * the past, but on the phone of somebody who has had the app for months and
 * whose likes, playlists and unuploaded play events are the thing being opened.
 *
 * So it runs the real migration over a real version three database with rows in
 * it, and then asks Room whether the result is the version four it expected.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MuseroomDatabase::class.java,
    )

    private companion object {
        const val NAME = "migration-test.db"
    }

    @Test
    fun theShelvesArriveWithoutDisturbingTheLibrary() {
        helper.createDatabase(NAME, 3).use { old ->
            old.execSQL(
                """
                INSERT INTO library_songs
                (id, title, artist, album, durationMs, cover, liked, likedAt, playedAt, plays)
                VALUES ('abc', 'Nights', 'Frank Ocean', 'Blonde', 307000, 'cover', 1, 90, 100, 4)
                """.trimIndent(),
            )
            old.execSQL("INSERT INTO playlists (name, createdAt) VALUES ('Late drives', 7)")
            old.execSQL(
                "INSERT INTO playlist_songs (playlistId, songId, position) VALUES (1, 'abc', 0)",
            )
        }

        // Validating rather than only running it: this is what catches a
        // CREATE TABLE that is one default or one nullability away from what
        // the entity declares, which is the failure that only shows up later.
        val migrated = helper.runMigrationsAndValidate(NAME, 4, true, *MuseroomDatabase.MIGRATIONS)

        migrated.query("SELECT liked, plays, playedAt FROM library_songs WHERE id = 'abc'").use {
            assertTrue("the song survived the upgrade", it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertEquals(4, it.getInt(1))
            assertEquals(100, it.getLong(2))
        }
        migrated.query("SELECT name FROM playlists").use {
            assertTrue(it.moveToFirst())
            assertEquals("Late drives", it.getString(0))
        }
        migrated.query("SELECT songId FROM playlist_songs WHERE playlistId = 1").use {
            assertTrue("the list still knows what is in it", it.moveToFirst())
            assertEquals("abc", it.getString(0))
        }

        // The three new tables exist and are empty, which is the whole of what
        // the upgrade was for.
        listOf("saved_albums", "followed_artists", "downloads").forEach { table ->
            migrated.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                assertEquals("$table starts empty", 0, it.getInt(0))
            }
        }
        migrated.close()
    }
}
