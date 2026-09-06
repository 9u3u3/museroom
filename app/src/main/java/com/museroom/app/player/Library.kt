package com.museroom.app.player

import android.content.Context
import com.museroom.app.data.LibrarySongEntity
import com.museroom.app.data.MuseroomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What this phone knows about songs, as opposed to what it is playing.
 *
 * Museroom has counted listening since long before it could play anything, but
 * those counts came from reading other apps and hold a title and an artist with
 * no way to play them back. This holds the other kind: songs played here, which
 * have an id, and can therefore be offered again, suggested from, or kept.
 *
 * Liked and recent and everything are one table read three ways, because a song
 * liked in one list and unknown in another is a bug waiting to be reported.
 */
object Library {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var dao: com.museroom.app.data.LibraryDao? = null

    private val empty = kotlinx.coroutines.flow.MutableStateFlow<List<LocalPlayer.Track>>(emptyList())

    /** Most recently played here, which is what Home offers back. */
    var recent: StateFlow<List<LocalPlayer.Track>> = empty
        private set

    /** Kept on purpose, newest first. */
    var liked: StateFlow<List<LocalPlayer.Track>> = empty
        private set

    /** Everything, which is the Songs shelf. */
    var songs: StateFlow<List<LocalPlayer.Track>> = empty
        private set

    var likedCount: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0)
        private set

    var songCount: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0)
        private set

    fun attach(context: Context) {
        if (dao != null) return
        val library = MuseroomDatabase.get(context).library()
        dao = library
        recent = library.recent().map { it.map(::asTrack) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
        liked = library.liked().map { it.map(::asTrack) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
        songs = library.all().map { it.map(::asTrack) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
        likedCount = library.likedCount().stateIn(scope, SharingStarted.Eagerly, 0)
        songCount = library.count().stateIn(scope, SharingStarted.Eagerly, 0)
    }

    /** Notes that a track was played, which is a fact rather than a preference. */
    fun played(track: LocalPlayer.Track) {
        val library = dao ?: return
        if (track.id.isBlank()) return
        scope.launch {
            library.played(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = "",
                durationMs = track.durationMs,
                cover = track.cover,
                at = System.currentTimeMillis(),
            )
        }
    }

    /** Whether this song is kept, as a question the heart can keep asking. */
    fun likedFlow(id: String): Flow<Boolean> =
        dao?.likedFlow(id)?.map { it == true } ?: kotlinx.coroutines.flow.flowOf(false)

    /**
     * Turns keeping a song on or off.
     *
     * A song can be liked before it has ever been played here, straight from a
     * search result, so this writes the row if there is not one already.
     */
    fun toggleLike(track: LocalPlayer.Track) {
        val library = dao ?: return
        if (track.id.isBlank()) return
        scope.launch {
            val now = System.currentTimeMillis()
            val existing = library.song(track.id)
            if (existing == null) {
                library.put(
                    LibrarySongEntity(
                        id = track.id,
                        title = track.title,
                        artist = track.artist,
                        album = "",
                        durationMs = track.durationMs,
                        cover = track.cover,
                        liked = true,
                        likedAt = now,
                    ),
                )
            } else {
                library.setLiked(track.id, !existing.liked, now)
            }
        }
    }

    /** Clears everything nobody chose to keep. */
    fun forgetUnliked() {
        val library = dao ?: return
        scope.launch { library.forgetUnliked() }
    }

    private fun asTrack(row: LibrarySongEntity) = LocalPlayer.Track(
        id = row.id,
        title = row.title,
        artist = row.artist,
        durationMs = row.durationMs,
        cover = row.cover,
    )
}
