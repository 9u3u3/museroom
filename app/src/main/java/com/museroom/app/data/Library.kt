package com.museroom.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A song Museroom has played, or that somebody asked it to keep.
 *
 * One table rather than three. Liked songs, recently played and everything ever
 * played are the same rows read in different orders, and splitting them would
 * mean a song could be liked in one place and unknown in another. The video id
 * is the key because it is what the player actually needs; a title and an
 * artist are for people.
 */
@Entity(
    tableName = "library_songs",
    indices = [Index("liked"), Index("playedAt"), Index("likedAt")],
)
data class LibrarySongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val cover: String,
    @ColumnInfo(defaultValue = "0") val liked: Boolean = false,
    /** Zero until somebody likes it, so the shelf can be ordered by when. */
    @ColumnInfo(defaultValue = "0") val likedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val playedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val plays: Int = 0,
)

@Dao
interface LibraryDao {

    /**
     * Records a play without disturbing a like.
     *
     * Written as one statement rather than read-modify-write because two
     * screens and a service all reach this, and a like lost to a race is a
     * thing somebody has to notice and do again.
     */
    @Query(
        """
        INSERT INTO library_songs (id, title, artist, album, durationMs, cover, liked, likedAt, playedAt, plays)
        VALUES (:id, :title, :artist, :album, :durationMs, :cover, 0, 0, :at, 1)
        ON CONFLICT(id) DO UPDATE SET
            title = excluded.title,
            artist = excluded.artist,
            album = CASE WHEN excluded.album <> '' THEN excluded.album ELSE library_songs.album END,
            durationMs = CASE WHEN excluded.durationMs > 0 THEN excluded.durationMs ELSE library_songs.durationMs END,
            cover = CASE WHEN excluded.cover <> '' THEN excluded.cover ELSE library_songs.cover END,
            playedAt = excluded.playedAt,
            plays = library_songs.plays + 1
        """,
    )
    suspend fun played(
        id: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        cover: String,
        at: Long,
    )

    /** Liking a song we have never played still has to put it somewhere. */
    @Upsert
    suspend fun put(song: LibrarySongEntity)

    @Query("UPDATE library_songs SET liked = :liked, likedAt = :at WHERE id = :id")
    suspend fun setLiked(id: String, liked: Boolean, at: Long)

    @Query("SELECT * FROM library_songs WHERE id = :id")
    suspend fun song(id: String): LibrarySongEntity?

    @Query("SELECT liked FROM library_songs WHERE id = :id")
    fun likedFlow(id: String): Flow<Boolean?>

    @Query("SELECT * FROM library_songs WHERE liked = 1 ORDER BY likedAt DESC")
    fun liked(): Flow<List<LibrarySongEntity>>

    @Query("SELECT COUNT(*) FROM library_songs WHERE liked = 1")
    fun likedCount(): Flow<Int>

    /** Everything, most recently played first, with the never-played last. */
    @Query("SELECT * FROM library_songs ORDER BY playedAt DESC, title ASC")
    fun all(): Flow<List<LibrarySongEntity>>

    @Query("SELECT COUNT(*) FROM library_songs")
    fun count(): Flow<Int>

    @Query("SELECT * FROM library_songs WHERE playedAt > 0 ORDER BY playedAt DESC LIMIT :limit")
    fun recent(limit: Int = 30): Flow<List<LibrarySongEntity>>

    @Query("SELECT * FROM library_songs WHERE playedAt > 0 ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recentNow(limit: Int = 30): List<LibrarySongEntity>

    @Query("DELETE FROM library_songs WHERE liked = 0")
    suspend fun forgetUnliked()
}

/**
 * A list somebody made.
 *
 * Nothing about it is synced anywhere. A playlist here is this phone's, which
 * is the honest shape while there is no YouTube account behind the app; a
 * playlist that quietly failed to reach an account would be worse than one that
 * never claimed to.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/**
 * A song's place in a list.
 *
 * Position is stored rather than inferred from insertion order, because the
 * whole point of a playlist is that somebody decided what follows what.
 */
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index("playlistId"), Index("songId")],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: String,
    val position: Int,
)

/** A list and how much is in it, which is all a tile needs. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val songs: Int,
    /** The newest cover in it, for the tile. Empty while the list is. */
    val cover: String,
)

@Dao
interface PlaylistDao {

    @Insert
    suspend fun create(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun empty(id: Long)

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun playlist(id: Long): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT p.id AS id, p.name AS name,
               (SELECT COUNT(*) FROM playlist_songs WHERE playlistId = p.id) AS songs,
               COALESCE((
                   SELECT s.cover FROM playlist_songs ps
                   JOIN library_songs s ON s.id = ps.songId
                   WHERE ps.playlistId = p.id AND s.cover <> ''
                   ORDER BY ps.position DESC LIMIT 1
               ), '') AS cover
        FROM playlists p
        ORDER BY p.createdAt DESC
        """,
    )
    fun summaries(): Flow<List<PlaylistSummary>>

    @Query(
        """
        SELECT s.* FROM playlist_songs ps
        JOIN library_songs s ON s.id = ps.songId
        WHERE ps.playlistId = :id
        ORDER BY ps.position ASC
        """,
    )
    fun songsIn(id: Long): Flow<List<LibrarySongEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :id")
    suspend fun nextPosition(id: Long): Int

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun add(entry: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id AND songId = :songId")
    suspend fun remove(id: Long, songId: String)
}
