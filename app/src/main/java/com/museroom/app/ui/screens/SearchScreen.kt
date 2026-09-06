package com.museroom.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import com.museroom.app.player.LocalPlayer
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoIcons
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
 */
@Composable
fun SearchScreen(onClose: () -> Unit, onOpenArtist: (String) -> Unit = {}) {
    val c = Neo.colors
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LocalPlayer.Track>>(emptyList()) }
    var looking by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf("") }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // The pause is the whole design of this. Long enough that typing a word is
    // one search rather than five, short enough that it never feels like
    // waiting for permission.
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(420)
        looking = true
        val found = Playback.search(text)
        if (found.isNotEmpty()) {
            recents.remove(text)
            recents.add(0, text)
            while (recents.size > 8) recents.removeAt(recents.lastIndex)
        }
        results = found
        asked = text
        looking = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
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

        Spacer(Modifier.height(14.dp))

        when {
            looking && results.isEmpty() -> Hint("Looking…")

            results.isEmpty() && query.trim().length >= 2 && asked == query.trim() ->
                Hint("Nothing for “${query.trim()}”.")

            results.isEmpty() -> Recents(onPick = { query = it })

            else -> {
                val playing by Playback.current.collectAsStateWithLifecycle()
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(results, key = { _, t -> t.id }) { i, track ->
                        TrackRow(
                            track = track,
                            playing = playing?.id == track.id,
                            // Each row arrives a beat after the one above it, so
                            // a page of results reads as a list being dealt out
                            // rather than a block appearing.
                            appearAfter = i,
                            onClick = {
                                keyboard?.hide()
                                Playback.play(results, i, from = "Search")
                            },
                            trailing = { Heart(track, size = 20) },
                            onOpenArtist = onOpenArtist,
                        )
                    }
                    item { Spacer(Modifier.height(120.dp)) }
                }
            }
        }
    }
}

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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recents.forEach { text ->
                NeoPill(
                    text = text,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(text) },
                )
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
) {
    val c = Neo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .riseIn(appearAfter)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TrackCover(track.id, track.artworkUrl, Modifier.size(46.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 14.sp,
                color = if (playing) c.violet else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val goes = onOpenArtist != null && track.artistId.isNotBlank() && subtitle == null
            Text(
                subtitle ?: listOfNotNull(
                    track.artist.takeIf { it.isNotBlank() },
                    track.durationMs.takeIf { it > 0 }?.let(::clockOf),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = if (goes) c.violet.copy(alpha = 0.85f) else c.ink.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (!goes) Modifier else Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpenArtist!!(track.artistId) },
            )
        }
        // Both, not one or the other. The bars say which row this is and the
        // heart is a thing to press, and hiding the control on the row somebody
        // is most likely to have an opinion about is exactly backwards.
        if (playing) Bars()
        trailing?.invoke()
    }
}
