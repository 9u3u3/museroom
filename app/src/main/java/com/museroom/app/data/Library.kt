package com.museroom.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
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
