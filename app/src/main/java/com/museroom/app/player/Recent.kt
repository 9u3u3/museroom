package com.museroom.app.player

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The last things played here, kept so the app has something to open with.
 *
 * Museroom has counted minutes since long before it could play anything, but
 * those minutes came from reading other apps, so they are a title and an artist
 * and no way to play them back. A track played through Museroom's own player
 * has an id, and an id is enough to build a whole screen out of: what to offer
 * again, and what to suggest next.
 *
 * Deliberately small and deliberately local. This is a shelf, not a library,
 * and the library is a later piece of work with a database behind it.
 */
object Recent {

    @Serializable
    private data class Saved(
        val id: String,
        val title: String,
        val artist: String,
        val cover: String,
        val at: Long,
    )

    /** Enough to fill a couple of rows without becoming a history nobody asked for. */
    private const val KEEP = 30

    private val json = Json { ignoreUnknownKeys = true }

    private val _tracks = MutableStateFlow<List<LocalPlayer.Track>>(emptyList())
    val tracks: StateFlow<List<LocalPlayer.Track>> = _tracks.asStateFlow()

    private var app: Context? = null

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        _tracks.value = read().map {
            LocalPlayer.Track(it.id, it.title, it.artist, cover = it.cover)
        }
    }

    /**
     * Remembers a track, moving it to the front if it was already there.
     *
     * Playing something again says more about it than playing it the first
     * time did, so a repeat is a promotion rather than a duplicate.
     */
    fun played(track: LocalPlayer.Track) {
        if (track.id.isBlank()) return
        val now = System.currentTimeMillis()
        val kept = read().filterNot { it.id == track.id }
        val updated = (
            listOf(Saved(track.id, track.title, track.artist, track.cover, now)) + kept
            ).take(KEEP)
        write(updated)
        _tracks.value = updated.map {
            LocalPlayer.Track(it.id, it.title, it.artist, cover = it.cover)
        }
    }

    fun forget() {
        write(emptyList())
        _tracks.value = emptyList()
    }

    private fun read(): List<Saved> {
        val text = prefs()?.getString(KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Saved.serializer()), text)
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<Saved>) {
        prefs()?.edit()
            ?.putString(KEY, json.encodeToString(ListSerializer(Saved.serializer()), list))
            ?.apply()
    }

    private fun prefs() = app?.getSharedPreferences("recent_tracks", Context.MODE_PRIVATE)

    private const val KEY = "tracks"
}
