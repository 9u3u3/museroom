package com.museroom.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.player.Downloads
import com.museroom.app.player.LocalPlayer
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoSheet
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.ui.kit.SheetOption
import com.museroom.app.ui.kit.SheetTitle

/**
 * One song, and everything there is to do with one.
 *
 * The design ends every row with three dots and they all open this. Having one
 * sheet rather than a menu per screen is what stops the queue's dots and the
 * album's dots offering different things about the same track.
 */
@Composable
fun TrackSheet(track: LocalPlayer.Track, onDismiss: () -> Unit) {
    val c = Neo.colors
    var saving by remember { mutableStateOf(false) }
    val have by Downloads.have.collectAsStateWithLifecycle()
    val kept = track.id in have

    if (saving) {
        SaveToPlaylist(track) { saving = false; onDismiss() }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NeoSheet(onDismiss = onDismiss) {
            SheetTitle(track.title)
            Text(
                listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.ink.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(6.dp))

            SheetOption("Play next", swatch = c.lime) {
                Playback.playNext(track)
                onDismiss()
            }
            SheetOption("Add to queue", swatch = c.violet) {
                Playback.enqueue(track)
                onDismiss()
            }
            SheetOption("Save to a playlist", swatch = c.sky) { saving = true }
            SheetOption(
                if (kept) "Remove the download" else "Download",
                swatch = c.pink,
            ) {
                if (kept) Downloads.remove(track.id) else Downloads.start(track)
                onDismiss()
            }

            Spacer(Modifier.size(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeoButton("Done", small = true, tone = NeoTone.Paper, onClick = onDismiss)
            }
        }
    }
}
