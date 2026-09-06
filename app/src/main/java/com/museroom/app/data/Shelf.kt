package com.museroom.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A record somebody put on their shelf.
 *
 * Only what a tile needs is kept. The track list is not: an album's contents
 * are a page on YouTube's side that can gain a remaster or lose a licence, and
 * a copy taken the day it was saved would quietly become wrong. What is worth
 * keeping is the fact that this person wanted it, which is the one thing
 * YouTube does not know.
 */
@Entity(tableName = "saved_albums")
data class SavedAlbumEntity(
    @PrimaryKey val browseId: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val cover: String,
    /** "Album • 2016", as the page wrote it, so the tile reads the same. */
    val detail: String,
    val savedAt: Long,
)

/**
 * Somebody worth following.
 *
 * This is Museroom's own following, not YouTube's, and the screen says so.
 * Signing in to YouTube would let the two be the same list; until then, a
 * follow that silently failed to reach an account would be worse than one that
 * never claimed to be there.
 */
@Entity(tableName = "followed_artists")
data class FollowedArtistEntity(
    @PrimaryKey val browseId: String,
    val name: String,
    val cover: String,
    val followedAt: Long,
)

/**
 * A song whose bytes are on this phone.
 *
 * The row exists only once the file is whole. A half-finished download is a
 * `.part` file and nothing else, because the entire point of this table is to
 * answer "can this be played with the radio off", and a maybe is a no.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val bytes: Long,
    val at: Long,
)

@Dao
interface ShelfDao {

    @Upsert
    suspend fun save(album: SavedAlbumEntity)

    @Query("DELETE FROM saved_albums WHERE browseId = :browseId")
    suspend fun unsave(browseId: String)

    @Query("SELECT * FROM saved_albums ORDER BY savedAt DESC")
    fun albums(): Flow<List<SavedAlbumEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_albums WHERE browseId = :browseId)")
    fun savedFlow(browseId: String): Flow<Boolean>

    @Upsert
    suspend fun follow(artist: FollowedArtistEntity)

    @Query("DELETE FROM followed_artists WHERE browseId = :browseId")
    suspend fun unfollow(browseId: String)

    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    fun artists(): Flow<List<FollowedArtistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE browseId = :browseId)")
    fun followedFlow(browseId: String): Flow<Boolean>

    @Upsert
    suspend fun kept(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE songId = :songId")
    suspend fun dropped(songId: String)

    @Query("SELECT songId FROM downloads")
    fun downloadedIds(): Flow<List<String>>

    @Query("SELECT songId FROM downloads")
    suspend fun downloadedNow(): List<String>

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM downloads")
    fun downloadedBytes(): Flow<Long>

    /**
     * The songs held offline, newest first.
     *
     * An inner join rather than a left one: a download whose song row is gone
     * has nothing to draw, and the only way that happens is a bug worth seeing
     * as an absence rather than as a row of blanks.
     */
    @Query(
        """
        SELECT s.* FROM downloads d
        JOIN library_songs s ON s.id = d.songId
        ORDER BY d.at DESC
        """,
    )
    fun downloaded(): Flow<List<LibrarySongEntity>>

    @Query("DELETE FROM downloads")
    suspend fun dropAll()
}
