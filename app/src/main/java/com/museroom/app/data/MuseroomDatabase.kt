package com.museroom.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlayEventEntity::class,
        ListeningSessionEntity::class,
        LibrarySongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        SavedAlbumEntity::class,
        FollowedArtistEntity::class,
        DownloadEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MuseroomDatabase : RoomDatabase() {

    abstract fun dao(): MuseroomDao

    abstract fun library(): LibraryDao

    abstract fun playlists(): PlaylistDao

    abstract fun shelf(): ShelfDao

    companion object {

        /**
         * Adds the library without touching what is already here.
         *
         * Destructive migration would be a line shorter and would throw away
         * play events that have not been uploaded yet, which are minutes
         * somebody listened to and would never get back.
         */
        private val TO_LIBRARY = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS library_songs (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        cover TEXT NOT NULL,
                        liked INTEGER NOT NULL DEFAULT 0,
                        likedAt INTEGER NOT NULL DEFAULT 0,
                        playedAt INTEGER NOT NULL DEFAULT 0,
                        plays INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_songs_liked ON library_songs (liked)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_songs_playedAt ON library_songs (playedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_songs_likedAt ON library_songs (likedAt)")
            }
        }

        /** Lists somebody made, added without disturbing the songs. */
        private val TO_PLAYLISTS = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlists (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_songs (
                        playlistId INTEGER NOT NULL,
                        songId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(playlistId, songId)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_playlistId ON playlist_songs (playlistId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_songId ON playlist_songs (songId)")
            }
        }

        /**
         * Records, artists and downloads: three tables of "somebody chose
         * this", added in one step because they arrived in one release.
         */
        private val TO_SHELVES = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_albums (
                        browseId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        artistId TEXT NOT NULL,
                        cover TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        savedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS followed_artists (
                        browseId TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        cover TEXT NOT NULL,
                        followedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloads (
                        songId TEXT NOT NULL PRIMARY KEY,
                        bytes INTEGER NOT NULL,
                        at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Every step, in order, in one place.
         *
         * Named rather than listed at the call site so that the migration test
         * runs the same array the app does. A test that rebuilt the list would
         * be testing a copy, and the copy is not what ships.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(TO_LIBRARY, TO_PLAYLISTS, TO_SHELVES)

        @Volatile
        private var instance: MuseroomDatabase? = null

        fun get(context: Context): MuseroomDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MuseroomDatabase::class.java,
                    "museroom.db",
                ).addMigrations(*MIGRATIONS).build().also { instance = it }
            }
    }
}
