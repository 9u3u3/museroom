package com.museroom.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.player.InnerTube
import com.museroom.app.player.LocalPlayer
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoChip
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoPill
import com.museroom.app.ui.kit.hardShadow
import kotlinx.coroutines.delay

/** Queries typed this session, newest first. Enough to get back to a search. */
private val recents = mutableListOf<String>()

/**
 * Finding something to play.
 *
 * Searching happens while you type rather than when you press a button, with
 * enough of a pause that a half-typed word is not a request. The alternative is
 * a screen where the most common action needs two hands.
 *
 * With no chip pressed the search comes back in shelves, with the one answer
 * YouTube thinks you meant on top. That card matters more than it looks:
 * somebody typing an artist's name wants the artist, and three of their songs
 * above them is the thing it exists to stop.
 */
@Composable
fun SearchScreen(
    onClose: () -> Unit,
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
) {
    val c = Neo.colors
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(InnerTube.Filter.Everything) }
    var found by remember { mutableStateOf(InnerTube.Results()) }
    var looking by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf("") }
    val results = remember(found) { Playback.tracksOf(found.songs + found.videos) }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // The pause is the whole design of this. Long enough that typing a word is
    // one search rather than five, short enough that it never feels like
    // waiting for permission.
    LaunchedEffect(query, filter) {
        val text = query.trim()
        if (text.length < 2) {
            found = InnerTube.Results()
            return@LaunchedEffect
        }
        // No pause when the chip changed rather than the words: the query is
        // already typed and the person is waiting on a decision they just made.
        if (text != asked) delay(420)
        looking = true
        val answer = Playback.searchFor(text, filter)
        if (!answer.empty) {
            recents.remove(text)
            recents.add(0, text)
            while (recents.size > 8) recents.removeAt(recents.lastIndex)
        }
        found = answer
        asked = text
        looking = false
    }

    // Its own status-bar inset, because this is the one screen drawn with the
    // top bar hidden, and the top bar is where every other screen gets one.
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundIcon(NeoIcons.Back, "Back", onClick = onClose)

            val shape = RoundedCornerShape(percent = 50)
            Row(
                Modifier
                    .weight(1f)
                    .hardShadow(4.dp, c.ink, shape)
                    .clip(shape)
                    .background(c.card)
                    .border(3.dp, c.ink, shape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NeoIcon(NeoIcons.Search, size = 18.dp, color = c.ink.copy(alpha = 0.5f), weight = 2.8f)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                    cursorBrush = SolidColor(c.violet),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .padding(vertical = 15.dp),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                "Songs, artists, albums",
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.ink.copy(alpha = 0.42f),
                            )
                        }
                        inner()
                    },
                )
                if (query.isNotEmpty()) {
                    NeoIcon(
                        NeoIcons.Close,
                        size = 16.dp,
                        color = c.ink.copy(alpha = 0.45f),
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { query = "" },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // The chips are only useful once there is something to filter, so they
        // stay out of the way of an empty box.
        if (query.trim().length >= 2) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InnerTube.Filter.entries.forEach { option ->
                    NeoChip(
                        text = if (option == InnerTube.Filter.Everything) "All" else option.name,
                        selected = filter == option,
                        onClick = { filter = option },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        when {
            looking && found.empty -> Hint("Looking…")

            found.empty && query.trim().length >= 2 && asked == query.trim() ->
                Hint("Nothing for “${query.trim()}”.")

            found.empty -> Recents(onPick = { query = it })

            else -> {
                val playing by Playback.current.collectAsStateWithLifecycle()
                // Turned into tracks out here rather than inside the list,
                // because a lazy list's item blocks are not a place to remember
                // anything: the scope they run in is not composable.
                val songs = remember(found) { Playback.tracksOf(found.songs) }
                val videos = remember(found) { Playback.tracksOf(found.videos) }
                LazyColumn(Modifier.fillMaxSize()) {
                    found.top?.let { top ->
                        item {
                            Shelf("Top result · ${top.kind}")
                            TopCard(
                                top = top,
                                onOpen = {
                                    keyboard?.hide()
                                    when {
                                        top.browseId.startsWith("UC") -> onOpenArtist(top.browseId)
                                        top.browseId.startsWith("VL") -> onOpenPlaylist(top.browseId)
                                        top.browseId.isNotBlank() -> onOpenAlbum(top.browseId)
                                        else -> {
                                            val one = results.firstOrNull { it.id == top.videoId }
                                                ?: LocalPlayer.Track(
                                                    id = top.videoId,
                                                    title = top.title,
                                                    artist = top.subtitle,
                                                    cover = top.artworkUrl,
                                                )
                                            Playback.play(listOf(one), 0, from = "Search")
                                        }
                                    }
                                },
                            )
                        }
                    }

                    if (found.songs.isNotEmpty()) {
                        item { Shelf("Songs") }
                        itemsIndexed(songs, key = { _, t -> t.id + "-s" }) { i, track ->
                            TrackRow(
                                track = track,
                                playing = playing?.id == track.id,
                                // Each row arrives a beat after the one above it,
                                // so a page of results reads as a list being
                                // dealt out rather than a block appearing.
                                appearAfter = i,
                                onClick = {
                                    keyboard?.hide()
                                    Playback.play(songs, i, from = "Search")
                                },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Keep(track, size = 18)
                                        Heart(track, size = 20)
                                    }
                                },
                                onOpenArtist = onOpenArtist,
                                onOpenAlbum = onOpenAlbum,
                            )
                        }
                    }

                    if (found.albums.isNotEmpty()) {
                        item { Shelf("Albums") }
                        item { Cards(found.albums, onOpen = onOpenAlbum) }
                    }

                    if (found.artists.isNotEmpty()) {
                        item { Shelf("Artists") }
                        item { Cards(found.artists, round = true, onOpen = onOpenArtist) }
                    }

                    if (found.playlists.isNotEmpty()) {
                        item { Shelf("Playlists") }
                        item { Cards(found.playlists, onOpen = onOpenPlaylist) }
                    }

                    if (found.videos.isNotEmpty()) {
                        item { Shelf("Videos") }
                        itemsIndexed(videos, key = { _, t -> t.id + "-v" }) { i, track ->
                            TrackRow(
                                track = track,
                                playing = playing?.id == track.id,
                                appearAfter = i,
                                onClick = {
                                    keyboard?.hide()
                                    Playback.play(videos, i, from = "Search")
                                },
                                trailing = { Heart(track, size = 20) },
                                onOpenArtist = onOpenArtist,
                            )
                        }
                    }

                    item { Spacer(Modifier.height(120.dp)) }
                }
            }
        }
    }
}

/** A heading over one kind of answer. */
@Composable
private fun Shelf(text: String) {
    com.museroom.app.ui.kit.Shelf(text)
}

/** The one answer YouTube thinks you meant, drawn larger than the rest. */
@Composable
private fun TopCard(top: InnerTube.Top, onOpen: () -> Unit) {
    val c = Neo.colors
    com.museroom.app.ui.kit.NeoCard(radius = 18.dp, shadow = 5.dp, padding = 12.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpen,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TrackCover(
                top.browseId.ifBlank { top.videoId },
                top.artworkUrl.ifBlank { null },
                Modifier.size(74.dp),
                // Round for a person, square for a record, which is the same
                // shorthand the library shelves use.
                radius = if (top.browseId.startsWith("UC")) 999.dp else 14.dp,
                shadow = 4.dp,
                stroke = 3.dp,
                dot = 9.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    top.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontSize = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (top.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        top.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = c.ink.copy(alpha = 0.6f),
                    )
                }
            }
            // A lime button rather than a bare glyph. This card exists so the
            // commonest search is one tap, and a tap target has to look like
            // one.
            com.museroom.app.ui.kit.NeoButton(
                text = "Open",
                small = true,
                tone = com.museroom.app.ui.kit.NeoTone.Lime,
                onClick = onOpen,
            )
        }
    }
}

/** A row of records or people, scrolled sideways. */
@Composable
private fun Cards(
    cards: List<InnerTube.Card>,
    round: Boolean = false,
    onOpen: (String) -> Unit,
) {
    val c = Neo.colors
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cards, key = { it.browseId }) { card ->
            Column(
                Modifier
                    .width(118.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onOpen(card.browseId) },
            ) {
                TrackCover(
                    card.browseId,
                    card.artworkUrl.ifBlank { null },
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    radius = if (round) 999.dp else 14.dp,
                    shadow = 4.dp,
                    stroke = 3.dp,
                    dot = 11.dp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = c.ink.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Recents(onPick: (String) -> Unit) {
    if (recents.isEmpty()) {
        Hint("Everything on YouTube Music, and it plays here.")
        return
    }
    val c = Neo.colors
    Column {
        Text(
            "RECENT",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W900,
            letterSpacing = 1.6.sp,
            color = c.ink.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recents.forEach { text ->
                NeoChip(text, selected = false, onClick = { onPick(text) }, caps = false)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Neo.colors.ink.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * One track, in a list.
 *
 * The same row on every screen that lists music, which is what the design asks
 * for and also what stops eight screens each inventing a slightly different
 * idea of what a song looks like.
 */
@Composable
fun TrackRow(
    track: LocalPlayer.Track,
    playing: Boolean,
    onClick: () -> Unit,
    appearAfter: Int = 0,
    trailing: (@Composable () -> Unit)? = null,
    onOpenArtist: ((String) -> Unit)? = null,
    /**
     * What the second line says, when the usual answer would be noise.
     *
     * On an album page the artist and the record are printed at the top, so
     * repeating them on all twenty rows says nothing and crowds out the one
     * thing the row does know on its own, which is how long it is.
     */
    subtitle: String? = null,
    /**
     * Its place on a record, shown instead of a cover.
     *
     * Twenty rows of the same sleeve is twenty copies of the picture already at
     * the top of the page. A number says the one thing the cover cannot: which
     * track this is.
     */
    number: Int? = null,
    onOpenAlbum: ((String) -> Unit)? = null,
    /**
     * The playing row, drawn as a card rather than as a coloured line.
     *
     * On an album and in the queue it is the one row that is a different kind
     * of thing from the rows around it — everything else is a song you could
     * play, and this is the song playing — so the design gives it its own
     * edges instead of a tint the eye has to hunt for.
     */
    highlight: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Neo.colors
    val lit = highlight && playing
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .riseIn(appearAfter)
            .then(
                if (lit) {
                    Modifier
                        .padding(vertical = 4.dp)
                        .hardShadow(4.dp, c.onAccent, shape)
                        .clip(shape)
                        .background(c.lime)
                        .border(3.dp, c.onAccent, shape)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(
                horizontal = if (lit) 10.dp else 0.dp,
                vertical = if (lit) 9.dp else 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        val ink = if (lit) c.onAccent else c.ink
        if (number != null) {
            Box(Modifier.size(width = 26.dp, height = 46.dp), contentAlignment = Alignment.Center) {
                MonoText("$number", size = 13, color = ink.copy(alpha = 0.5f))
            }
        } else {
            TrackCover(track.id, track.artworkUrl, Modifier.size(46.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 14.sp,
                color = if (lit) c.onAccent else if (playing) c.violet else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = ink.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                // The artist and the record are two different places, so they
                // are two different things to press rather than one line that
                // has to pick which one it meant.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Leg(track.artist, track.artistId, onOpenArtist)
                    if (track.artist.isNotBlank() && track.album.isNotBlank()) Dot()
                    Leg(track.album, track.albumId, onOpenAlbum)
                    val length = track.durationMs.takeIf { it > 0 }?.let(::clockOf)
                    if (length != null) {
                        if (track.artist.isNotBlank() || track.album.isNotBlank()) Dot()
                        Leg(length, "", null)
                    }
                }
            }
        }
        // Both, not one or the other. The bars say which row this is and the
        // heart is a thing to press, and hiding the control on the row somebody
        // is most likely to have an opinion about is exactly backwards.
        if (playing) Bars(color = if (lit) c.onAccent else c.violet)
        trailing?.invoke()
    }
}

/** One part of a row's second line, violet when it goes somewhere. */
@Composable
private fun Leg(text: String, id: String, onOpen: ((String) -> Unit)?) {
    if (text.isBlank()) return
    val c = Neo.colors
    val goes = onOpen != null && id.isNotBlank()
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontSize = 11.sp,
        color = if (goes) c.violet.copy(alpha = 0.85f) else c.ink.copy(alpha = 0.6f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (!goes) Modifier else Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onOpen!!(id) },
    )
}

@Composable
private fun Dot() = Text(
    " · ",
    style = MaterialTheme.typography.bodySmall,
    fontSize = 11.sp,
    color = Neo.colors.ink.copy(alpha = 0.45f),
)
