package com.museroom.app.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * YouTube's search, asked as a browser.
 *
 * Playback and search sit behind different doors, and it is worth saying which
 * is which. Streaming data is what the bot check guards, and getting at it needs
 * the token machinery in `Extraction`. Search does not. So Museroom asks as a
 * browser for words and leaves the audio to the extractor, which is an honest
 * description of what each request is for and keeps the cheap half cheap.
 */
object InnerTube {

    /**
     * Who we say we are when we search.
     *
     * The web client is useless for audio and exactly right for everything
     * else, which is the whole point of the split above.
     */
    private const val CLIENT = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20250310.01.00"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private const val SEARCH = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val NEXT = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
    private const val BROWSE = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"

    /**
     * YouTube Music's "songs" filter, as the site itself sends it.
     *
     * Without it a search returns music videos, which are a different recording
     * of the same song with a different length. In a room that difference is
     * everybody hearing a slightly different track and the follow loop trying to
     * correct for something that is not drift.
     */
    private const val SONGS_ONLY = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="

    /**
     * The other tabs of YouTube Music's own search, as the site sends them.
     *
     * These are protobuf blobs rather than anything readable, which is why they
     * are written out rather than built: they are what the site puts on the
     * wire when you press one of its filter chips, and the endpoint accepts
     * nothing else.
     */
    enum class Filter(val params: String?) {
        /** Everything, in shelves, with a top result. What an empty box shows. */
        Everything(null),
        Songs(SONGS_ONLY),
        Albums("EgWKAQIYAWoKEAkQChAFEAMQBA=="),
        Artists("EgWKAQIgAWoKEAkQChAFEAMQBA=="),
        Playlists("EgWKAQIoAWoKEAkQChAFEAMQBA=="),
        Videos("EgWKAQIQAWoKEAkQChAFEAMQBA=="),
    }

    /**
     * What one search came back with.
     *
     * Kept as separate lists rather than one list of a sealed type because the
     * screen draws them under separate headings, and flattening them only to
     * group them again is work that exists to be undone.
     */
    data class Results(
        val top: Top? = null,
        val songs: List<Found> = emptyList(),
        val videos: List<Found> = emptyList(),
        val albums: List<Card> = emptyList(),
        val artists: List<Card> = emptyList(),
        val playlists: List<Card> = emptyList(),
    ) {
        val empty: Boolean
            get() = top == null && songs.isEmpty() && videos.isEmpty() &&
                albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
    }

    /**
     * The one answer YouTube thinks the search was really for.
     *
     * It has its own renderer because it is its own idea: a person typing an
     * artist's name wants the artist, and burying that under three of their
     * songs is the thing the card exists to stop.
     */
    data class Top(
        val kind: String,
        val title: String,
        val subtitle: String,
        val artworkUrl: String,
        /** One of these two is set. Which one is what pressing it does. */
        val browseId: String = "",
        val videoId: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** A song somebody could choose. */
    data class Found(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        /**
         * The square cover, which the search response carries and the video
         * still does not. YouTube's own thumbnail for a song is a 4:3 frame
         * with the sleeve boxed inside it, so using it means either black bars
         * or cropping the art.
         */
        val artworkUrl: String = "",
        /** Where this song came from, so a result is somewhere you can go. */
        val artistId: String = "",
        val albumId: String = "",
    )

    /** An album or a single, as a card on an artist's page. */
    data class Card(
        val browseId: String,
        val title: String,
        val subtitle: String,
        val artworkUrl: String,
    )

    data class Album(
        val browseId: String,
        val title: String,
        val artist: String,
        val artistId: String,
        /** "Album • 2018", as YouTube Music writes it. */
        val kind: String,
        /** "21 songs • 1 hour, 14 minutes". */
        val detail: String,
        val artworkUrl: String,
        val tracks: List<Found>,
    )

    data class Artist(
        val browseId: String,
        val name: String,
        val listeners: String,
        val artworkUrl: String,
        val songs: List<Found>,
        val albums: List<Card>,
    )

    /** Songs matching a query, best first, or empty if the search found none. */
    fun search(query: String, limit: Int = 20): List<Found> =
        results(ask(query, Filter.Songs), limit)

    /** One search under one filter, read into whichever lists it filled. */
    fun searchFor(query: String, filter: Filter, limit: Int = 20): Results {
        val payload = ask(query, filter)
        if (payload.isBlank()) return Results()
        return when (filter) {
            Filter.Everything -> everything(payload, limit)
            Filter.Songs -> Results(songs = results(payload, limit))
            Filter.Videos -> Results(videos = results(payload, limit))
            Filter.Albums -> Results(albums = cards(payload, "ALBUM", limit))
            Filter.Artists -> Results(artists = cards(payload, "ARTIST", limit))
            Filter.Playlists -> Results(playlists = cards(payload, "PLAYLIST", limit))
        }
    }

    /** One search request. Empty on anything other than an answer. */
    private fun ask(query: String, filter: Filter): String {
        if (query.isBlank()) return ""
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("query", query)
            filter.params?.let { put("params", it) }
        }
        val request = Request.Builder()
            .url(SEARCH)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return http.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string().orEmpty() else ""
        }
    }

    /**
     * Fifty songs that go with this one.
     *
     * YouTube will build a radio around any track, and it will do it without an
     * account, which is the whole reason this is here. Museroom knows what you
     * actually played because it has been counting minutes since long before it
     * could play anything, so it can seed a set of suggestions from that rather
     * than showing an empty shelf to anybody who has not signed in to YouTube.
     */
    fun radio(seedId: String, limit: Int = 25): List<Found> {
        if (seedId.isBlank()) return emptyList()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("videoId", seedId)
            // The prefix is what turns "play this" into "play things like this".
            put("playlistId", "RDAMVM$seedId")
            put("isAudioOnly", true)
        }
        val request = Request.Builder()
            .url(NEXT)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        // The seed itself comes back first. It is the song they just heard, so
        // showing it as a suggestion would be a strange thing to do.
        return queued(text, limit + 1).filterNot { it.id == seedId }.take(limit)
    }

    /** One browse call, or an empty string when it did not answer. */
    private fun browse(browseId: String): String {
        if (browseId.isBlank()) return ""
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("browseId", browseId)
        }
        val request = Request.Builder()
            .url(BROWSE)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return http.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string().orEmpty() else ""
        }
    }

    fun album(browseId: String): Album? = albumFrom(browseId, browse(browseId))

    /**
     * An album page.
     *
     * The track rows here are shaped differently from search results: the
     * second line is a play count rather than an artist, and the length lives
     * in a fixed column instead of the title's own runs. So the artist comes
     * from the header, which is right anyway — every track on an album is by
     * whoever the album is by.
     */
    fun albumFrom(browseId: String, payload: String): Album? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val header = firstObject(root, "musicResponsiveHeaderRenderer") ?: return null
        val title = header["title"]?.let(::runsIn).orEmpty()
        if (title.isBlank()) return null
        val artist = header["straplineTextOne"]?.let(::runsIn).orEmpty()

        val tracks = mutableListOf<Found>()
        walkKey(root, "musicResponsiveListItemRenderer") { item ->
            val id = firstString(item, "videoId") ?: return@walkKey
            val name = item["flexColumns"]?.jsonArray?.firstOrNull()?.let(::runsIn).orEmpty()
            if (name.isBlank()) return@walkKey
            tracks += Found(
                id = id,
                title = name,
                artist = artist,
                album = title,
                durationMs = item["fixedColumns"]?.let(::runsIn)?.let(::clock) ?: 0,
                artworkUrl = enlarge(firstString(header["thumbnail"], "url").orEmpty()),
                artistId = firstBrowseId(header["straplineTextOne"], "ARTIST"),
                albumId = browseId,
            )
        }

        return Album(
            browseId = browseId,
            title = title,
            artist = artist,
            artistId = firstBrowseId(header["straplineTextOne"], "ARTIST"),
            kind = header["subtitle"]?.let(::runsIn).orEmpty(),
            detail = header["secondSubtitle"]?.let(::runsIn).orEmpty(),
            artworkUrl = enlarge(firstString(header["thumbnail"], "url").orEmpty()),
            tracks = tracks,
        )
    }

    fun artist(browseId: String): Artist? = artistFrom(browseId, browse(browseId))

    /**
     * An artist's page: the songs people actually play, and the records.
     *
     * The shelves are read for what they contain rather than by their headings,
     * because the headings are localised and move about. Songs are the rows;
     * everything with a browse id and a cover is a record.
     */
    fun artistFrom(browseId: String, payload: String): Artist? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val header = firstObject(root, "musicImmersiveHeaderRenderer")
            ?: firstObject(root, "musicVisualHeaderRenderer")
            ?: return null
        val name = header["title"]?.let(::runsIn).orEmpty()
        if (name.isBlank()) return null

        val songs = mutableListOf<Found>()
        walkKey(root, "musicResponsiveListItemRenderer") { item ->
            val id = firstString(item, "videoId") ?: return@walkKey
            val columns = item["flexColumns"]?.jsonArray ?: return@walkKey
            val title = columns.firstOrNull()?.let(::runsIn).orEmpty()
            if (title.isBlank()) return@walkKey
            songs += Found(
                id = id,
                title = title,
                artist = name,
                album = "",
                durationMs = item["fixedColumns"]?.let(::runsIn)?.let(::clock) ?: 0,
                artworkUrl = biggestThumbnail(item),
                artistId = browseId,
            )
        }

        val albums = mutableListOf<Card>()
        val seen = mutableSetOf<String>()
        walkKey(root, "musicTwoRowItemRenderer") { card ->
            val id = firstBrowseId(card, "ALBUM")
            if (id.isBlank() || !seen.add(id)) return@walkKey
            albums += Card(
                browseId = id,
                title = card["title"]?.let(::runsIn).orEmpty(),
                subtitle = card["subtitle"]?.let(::runsIn).orEmpty(),
                artworkUrl = biggestThumbnail(card),
            )
        }

        return Artist(
            browseId = browseId,
            name = name,
            listeners = header["monthlyListenerCount"]?.let(::runsIn).orEmpty(),
            artworkUrl = enlarge(firstString(header["thumbnail"], "url").orEmpty()),
            songs = songs,
            albums = albums,
        )
    }

    /** The first browse id under here whose page is of the kind asked for. */
    private fun firstBrowseId(
        element: kotlinx.serialization.json.JsonElement?,
        pageType: String,
    ): String {
        element ?: return ""
        var found = ""
        fun visit(e: kotlinx.serialization.json.JsonElement) {
            if (found.isNotEmpty()) return
            when (e) {
                is JsonObject -> {
                    val browse = e["browseEndpoint"] as? JsonObject
                    if (browse != null) {
                        val page = runCatching {
                            browse["browseEndpointContextSupportedConfigs"]!!.jsonObject[
                                "browseEndpointContextMusicConfig",
                            ]!!.jsonObject["pageType"]!!.jsonPrimitive.content
                        }.getOrNull().orEmpty()
                        if (page.endsWith(pageType)) {
                            found = runCatching { browse["browseId"]!!.jsonPrimitive.content }
                                .getOrDefault("")
                            if (found.isNotEmpty()) return
                        }
                    }
                    e.values.forEach { visit(it) }
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { visit(it) }
                else -> Unit
            }
        }
        visit(element)
        return found
    }

    private fun firstObject(
        element: kotlinx.serialization.json.JsonElement,
        key: String,
    ): JsonObject? {
        var found: JsonObject? = null
        fun visit(e: kotlinx.serialization.json.JsonElement) {
            if (found != null) return
            when (e) {
                is JsonObject -> {
                    (e[key] as? JsonObject)?.let { found = it; return }
                    e.values.forEach { visit(it) }
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { visit(it) }
                else -> Unit
            }
        }
        visit(element)
        return found
    }

    private fun walkKey(
        element: kotlinx.serialization.json.JsonElement,
        key: String,
        onItem: (JsonObject) -> Unit,
    ) {
        when (element) {
            is JsonObject -> for ((k, v) in element) {
                if (k == key && v is JsonObject) onItem(v) else walkKey(v, key, onItem)
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { walkKey(it, key, onItem) }
            else -> Unit
        }
    }

    /** The songs in a queue response, in the order the radio put them. */
    fun queued(payload: String, limit: Int = 25): List<Found> {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()
        val found = mutableListOf<Found>()
        walkQueue(root) { item ->
            if (found.size >= limit) return@walkQueue
            val id = runCatching { item["videoId"]!!.jsonPrimitive.content }.getOrNull()
                ?: return@walkQueue
            val title = item["title"]?.let(::runsIn).orEmpty()
            if (title.isBlank()) return@walkQueue
            val byline = item["longBylineText"]?.let(::runsIn).orEmpty()
                .split("•").map { it.trim() }.filter { it.isNotEmpty() }
            found += Found(
                id = id,
                title = title,
                artist = byline.firstOrNull().orEmpty(),
                // The second line is the album on a song and a view count on a
                // video, and a number of views is not an album.
                album = byline.getOrNull(1)?.takeUnless { it.contains(" views") }.orEmpty(),
                durationMs = item["lengthText"]?.let(::runsIn)?.let(::clock) ?: 0,
                artworkUrl = biggestThumbnail(item),
                artistId = firstBrowseId(item, "ARTIST"),
                albumId = firstBrowseId(item, "ALBUM"),
            )
        }
        return found
    }

    private fun walkQueue(
        element: kotlinx.serialization.json.JsonElement,
        onItem: (JsonObject) -> Unit,
    ) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                if (key == "playlistPanelVideoRenderer" && value is JsonObject) onItem(value)
                else walkQueue(value, onItem)
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { walkQueue(it, onItem) }
            else -> Unit
        }
    }

    /**
     * Reads the search response by hunting for the one renderer that matters
     * rather than by describing the whole tree.
     *
     * The tree is deep, it is versioned by nobody, and the shelves around a
     * result move about between releases. What has not moved is that a song is a
     * `musicResponsiveListItemRenderer` with a video id somewhere inside it and
     * its text in flex columns. Walking for that survives a redesign of
     * everything around it; a full description of the layout does not.
     */
    fun results(payload: String, limit: Int = 20): List<Found> {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()
        val found = mutableListOf<Found>()
        walk(root) { item ->
            if (found.size >= limit) return@walk
            val id = firstString(item, "videoId") ?: return@walk
            val columns = item["flexColumns"]?.jsonArray ?: return@walk
            val lines = columns.map { runsIn(it) }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return@walk
            val title = lines.first()
            val parts = lines.getOrNull(1)?.split("•")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
            val duration = parts.lastOrNull()?.let { clock(it) } ?: 0
            found += Found(
                id = id,
                title = title,
                artist = parts.firstOrNull().orEmpty(),
                album = if (parts.size > 2) parts[parts.size - 2] else "",
                durationMs = duration,
                artworkUrl = biggestThumbnail(item),
                artistId = firstBrowseId(item, "ARTIST"),
                albumId = firstBrowseId(item, "ALBUM"),
            )
        }
        return found
    }

    /**
     * An unfiltered search, sorted by what each row turns out to be.
     *
     * The shelves are not read by their headings. Headings are localised, they
     * move about between releases, and "Songs" and "Videos" are the same word
     * in enough languages to be a bad key. What does not move is what a row
     * carries: a video id means something playable, and a browse id whose page
     * type is an album means an album. So every row is classified by its own
     * endpoint and dropped in the right list.
     */
    fun everything(payload: String, limit: Int = 20): Results {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return Results()

        val songs = mutableListOf<Found>()
        val videos = mutableListOf<Found>()
        val albums = mutableListOf<Card>()
        val artists = mutableListOf<Card>()
        val playlists = mutableListOf<Card>()
        val seen = mutableSetOf<String>()

        // Read before the rows, because the rows need it.
        //
        // When the top result is an artist, the songs directly under it are
        // that artist's and their lines say so by leaving the name out — the
        // card above has just said it. Those rows have no artist of their own
        // to read, so this is where it comes from.
        val top = topResult(root)
        val whoseCard = top?.takeIf { it.kind == "Artist" }?.title.orEmpty()

        walk(root) { item ->
            val columns = item["flexColumns"]?.jsonArray
            val lines = columns?.map { runsIn(it) }?.filter { it.isNotBlank() } ?: return@walk
            val title = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@walk

            // Everything after the title, however it was split up.
            //
            // An unfiltered search is not consistent about this. The same
            // information arrives as one column with bullets in it on some
            // rows and as three separate columns on others, and reading only
            // the second column is how a song came to be by an artist called
            // "3:22". Flattening both shapes into one list of pieces means the
            // rest of this does not have to know which it got.
            val parts = lines.drop(1)
                .flatMap { it.split("•") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val subtitle = lines.drop(1).joinToString(" • ")

            val videoId = firstString(item, "videoId")
            if (videoId != null) {
                if (!seen.add("v:$videoId")) return@walk
                // An unfiltered search labels every row with what it is, so
                // the line reads "Song • Radiohead • OK Computer • 5:00" where
                // a filtered one would start at the artist. Taking the label
                // off is the difference between an artist named Radiohead and
                // an artist named Song.
                val said = withoutTheLabel(parts)

                // The pieces that can be recognised for certain are taken out
                // first, and whatever is left is words about the song.
                //
                // Position is no help here: the line runs artist, album,
                // length, plays on one row and length, plays on the next,
                // where the artist was left out because the card above named
                // them. A length looks like a length and a play count says
                // "plays", so both can be lifted out by what they are, and
                // then the first thing remaining really is the artist.
                val timed = said.firstNotNullOfOrNull { part ->
                    clock(part).takeIf { it > 0 }
                } ?: 0
                val words = said.filterNot { part ->
                    clock(part) > 0 || part.endsWith(" plays") || part.endsWith(" views")
                }
                val found = Found(
                    id = videoId,
                    title = title,
                    artist = words.firstOrNull() ?: whoseCard,
                    // An album only exists when there is something after the
                    // artist. One word is who made it, not what it is on.
                    album = if (words.size > 1) words.last() else "",
                    durationMs = if (timed > 0) {
                        timed
                    } else {
                        item["fixedColumns"]?.let(::runsIn)?.let(::clock) ?: 0
                    },
                    artworkUrl = biggestThumbnail(item),
                    artistId = firstBrowseId(item, "ARTIST"),
                    albumId = firstBrowseId(item, "ALBUM"),
                )
                // A view count where an album should be is how a music video
                // announces itself. It is a different recording at a different
                // length, so it belongs under its own heading.
                val kind = parts.firstOrNull().orEmpty()
                // A view count where an album should be, or a row that says so
                // itself. Either way it is a different recording at a different
                // length from the song of the same name.
                if (kind == "Video" || said.any { it.endsWith(" views") }) {
                    videos += found
                } else {
                    songs += found
                }
                return@walk
            }

            val album = firstBrowseId(item, "ALBUM")
            val artist = firstBrowseId(item, "ARTIST")
            val playlist = firstBrowseId(item, "PLAYLIST")
            val card = { id: String ->
                Card(id, title, subtitle, biggestThumbnail(item))
            }
            when {
                album.isNotBlank() && seen.add("b:$album") -> albums += card(album)
                artist.isNotBlank() && seen.add("b:$artist") -> artists += card(artist)
                playlist.isNotBlank() && seen.add("b:$playlist") -> playlists += card(playlist)
            }
        }

        return Results(
            top = top,
            songs = songs.take(limit),
            videos = videos.take(limit),
            albums = albums.take(limit),
            artists = artists.take(limit),
            playlists = playlists.take(limit),
        )
    }

    /**
     * The byline with its leading type word removed, when it has one.
     *
     * Matched against a list rather than by position, because a row whose
     * artist happens to be one word would otherwise lose the artist. English
     * only, which is safe here: every request goes out with `hl=en`.
     */
    private val LABELS = setOf("Song", "Video", "Album", "Single", "EP", "Playlist", "Artist")

    private fun withoutTheLabel(parts: List<String>): List<String> =
        if (parts.size > 1 && parts.first() in LABELS) parts.drop(1) else parts

    /** The card YouTube puts above everything else, when it offered one. */
    private fun topResult(root: JsonObject): Top? {
        val card = firstObject(root, "musicCardShelfRenderer") ?: return null
        val title = card["title"]?.let(::runsIn).orEmpty()
        if (title.isBlank()) return null
        val subtitle = card["subtitle"]?.let(::runsIn).orEmpty()
        val kind = subtitle.split("•").firstOrNull()?.trim().orEmpty()
        val videoId = firstString(card["onTap"], "videoId").orEmpty()
        val browseId = listOf("ARTIST", "ALBUM", "PLAYLIST")
            .firstNotNullOfOrNull { firstBrowseId(card, it).takeIf { id -> id.isNotBlank() } }
            .orEmpty()
        if (videoId.isBlank() && browseId.isBlank()) return null
        return Top(
            kind = kind.ifBlank { if (videoId.isNotBlank()) "Song" else "Result" },
            title = title,
            subtitle = subtitle.substringAfter("•", subtitle).trim(),
            artworkUrl = biggestThumbnail(card),
            browseId = browseId,
            videoId = videoId,
        )
    }

    /** Every row in a filtered search that leads to a page of the kind asked for. */
    fun cards(payload: String, pageType: String, limit: Int = 20): List<Card> {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()
        val cards = mutableListOf<Card>()
        val seen = mutableSetOf<String>()
        walk(root) { item ->
            if (cards.size >= limit) return@walk
            val id = firstBrowseId(item, pageType)
            if (id.isBlank() || !seen.add(id)) return@walk
            val lines = item["flexColumns"]?.jsonArray?.map { runsIn(it) }?.filter { it.isNotBlank() }
                ?: return@walk
            val title = lines.firstOrNull() ?: return@walk
            cards += Card(id, title, lines.getOrNull(1).orEmpty(), biggestThumbnail(item))
        }
        return cards
    }

    /**
     * A playlist page.
     *
     * Reuses the album header, which YouTube Music draws with the same
     * renderer, and differs in the one place that matters: on an album every
     * track is by the artist in the header, and on a playlist the artist is
     * whatever the row itself says. Reading it from the header there would
     * label a hundred artists as whoever made the list.
     */
    fun playlist(browseId: String): Album? = playlistFrom(browseId, browse(browseId))

    fun playlistFrom(browseId: String, payload: String): Album? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val header = firstObject(root, "musicResponsiveHeaderRenderer")
            ?: firstObject(root, "musicDetailHeaderRenderer")
            ?: return null
        val title = header["title"]?.let(::runsIn).orEmpty()
        if (title.isBlank()) return null
        val cover = enlarge(firstString(header["thumbnail"], "url").orEmpty())

        val tracks = mutableListOf<Found>()
        walkKey(root, "musicResponsiveListItemRenderer") { item ->
            val id = firstString(item, "videoId") ?: return@walkKey
            val columns = item["flexColumns"]?.jsonArray ?: return@walkKey
            val name = columns.firstOrNull()?.let(::runsIn).orEmpty()
            if (name.isBlank()) return@walkKey
            val byline = columns.getOrNull(1)?.let(::runsIn).orEmpty()
                .split("•").map { it.trim() }.filter { it.isNotEmpty() }
            tracks += Found(
                id = id,
                title = name,
                artist = byline.firstOrNull().orEmpty(),
                album = byline.getOrNull(1).orEmpty(),
                durationMs = item["fixedColumns"]?.let(::runsIn)?.let(::clock) ?: 0,
                artworkUrl = biggestThumbnail(item).ifBlank { cover },
                artistId = firstBrowseId(item, "ARTIST"),
                albumId = firstBrowseId(item, "ALBUM"),
            )
        }

        return Album(
            browseId = browseId,
            title = title,
            artist = header["straplineTextOne"]?.let(::runsIn).orEmpty(),
            artistId = "",
            kind = header["subtitle"]?.let(::runsIn).orEmpty(),
            detail = header["secondSubtitle"]?.let(::runsIn).orEmpty(),
            artworkUrl = cover,
            tracks = tracks,
        )
    }

    /**
     * The largest thumbnail anywhere inside one result.
     *
     * Largest rather than first: the same renderer offers the sleeve at several
     * sizes, and the small one is visibly soft behind a full-width player.
     */
    fun biggestThumbnail(item: JsonObject): String {
        var best = ""
        var area = 0
        fun visit(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is JsonObject -> {
                    val url = runCatching { e["url"]!!.jsonPrimitive.content }.getOrNull()
                    val width = runCatching { e["width"]!!.jsonPrimitive.content.toInt() }.getOrNull()
                    val height = runCatching { e["height"]!!.jsonPrimitive.content.toInt() }.getOrNull()
                    if (url != null && width != null && height != null && width * height > area) {
                        area = width * height
                        best = url
                    }
                    e.values.forEach { visit(it) }
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { visit(it) }
                else -> Unit
            }
        }
        visit(item)
        return enlarge(best)
    }

    /**
     * Asks Google's image host for the sleeve at a useful size.
     *
     * These URLs end in the dimensions they were rendered for, and a list ships
     * the sixty-pixel one. Blown up to the width of a phone that is visibly
     * soft, and the fix is a different number rather than a different request:
     * the host resizes on demand.
     */
    fun enlarge(url: String): String =
        if (url.isBlank()) url
        else Regex("=w\\d+-h\\d+").replace(url, "=w544-h544")

    /** Every musicResponsiveListItemRenderer in the tree, in the order it appears. */
    private fun walk(element: kotlinx.serialization.json.JsonElement, onItem: (JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                if (key == "musicResponsiveListItemRenderer" && value is JsonObject) onItem(value)
                else walk(value, onItem)
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { walk(it, onItem) }
            else -> Unit
        }
    }

    private fun firstString(element: kotlinx.serialization.json.JsonElement?, key: String): String? {
        element ?: return null
        when (element) {
            is JsonObject -> {
                for ((k, v) in element) {
                    if (k == key) {
                        val text = runCatching { v.jsonPrimitive.content }.getOrNull()
                        if (!text.isNullOrBlank()) return text
                    }
                    firstString(v, key)?.let { return it }
                }
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { child ->
                firstString(child, key)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    /** Everything a renderer's runs say, joined, wherever they are nested. */
    fun runsIn(element: kotlinx.serialization.json.JsonElement): String {
        val out = StringBuilder()
        fun visit(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k == "runs" && v is kotlinx.serialization.json.JsonArray) {
                        v.forEach { run ->
                            runCatching { run.jsonObject["text"]!!.jsonPrimitive.content }
                                .getOrNull()?.let { out.append(it) }
                        }
                    } else visit(v)
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { visit(it) }
                else -> Unit
            }
        }
        visit(element)
        return out.toString().trim()
    }

    /** "4:09" or "1:02:11" as milliseconds, or zero when it is not a time at all. */
    fun clock(text: String): Long {
        val parts = text.split(":")
        if (parts.size !in 2..3) return 0
        val numbers = parts.map { it.trim().toIntOrNull() ?: return 0 }
        val seconds = numbers.fold(0) { total, n -> total * 60 + n }
        return seconds * 1000L
    }
}
