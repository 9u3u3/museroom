package com.museroom.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.museroom.app.media.Artwork
import com.museroom.app.media.PlayOutcome
import com.museroom.app.media.PlayerCommands
import com.museroom.app.media.PlayerPreference
import com.museroom.app.media.Sources
import com.museroom.app.util.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * One person and what they are playing, wherever they came from.
 *
 * Artwork is looked up by track name rather than shared from their phone, so a
 * miss is silent and costs nothing. The progress bar extrapolates locally from
 * the position and the moment it was taken, so one message every fifteen seconds
 * still looks live.
 */
@Composable
fun ListenerRow(
    handle: String,
    title: String,
    artist: String,
    durationMs: Long,
    positionMs: Long,
    isPlaying: Boolean,
    updatedAt: String,
    sourceTrackId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preference = remember { PlayerPreference.get(context) }

    var art by remember(title, artist) { mutableStateOf<Bitmap?>(Artwork.cached(title, artist)) }
    var choosing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(title, artist) {
        if (art == null && title.isNotBlank()) art = Artwork.fetch(title, artist)
    }
    LaunchedEffect(updatedAt) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }

    fun playIn(packageName: String) {
        val label = Sources.label(packageName)
        outcome = "Asking $label…"
        scope.launch {
            // Always say what happened. Silence after a tap is indistinguishable
            // from a broken button, which is exactly how this failed before.
            outcome = when (
                val result = PlayerCommands.play(context, packageName, title, artist, sourceTrackId)
            ) {
                is PlayOutcome.Started -> "Playing in $label."
                is PlayOutcome.OpenedExact ->
                    "$label opened on the song but would not start itself. Press play."
                is PlayOutcome.Opened ->
                    "$label would not take the song, so it opened at a search. " +
                        "Tap the right result to play."
                is PlayOutcome.Failed -> result.reason
            }
        }
    }

    if (choosing) {
        PlayerChooser(
            onDismiss = { choosing = false },
            onChosen = { packageName, remember ->
                if (remember) preference.preferred = packageName
                choosing = false
                playIn(packageName)
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(art)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "@$handle",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Caption(if (isPlaying) "listening" else "paused")
                }
                Text(
                    text = title.ifBlank { "Nothing shared" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist.ifBlank { "Unknown artist" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (title.isNotBlank()) {
                    TextButton(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        onClick = {
                            val preferred = preference.preferred
                            if (preferred != null && PlayerCommands.isInstalled(context, preferred)) {
                                playIn(preferred)
                            } else {
                                choosing = true
                            }
                        },
                    ) { Text("Listen too") }
                }
            }
        }

        if (durationMs > 0 && title.isNotBlank()) {
            val position = livePosition(positionMs, updatedAt, isPlaying, nowMs)
                .coerceIn(0L, durationMs)
            Spacer(Modifier.size(6.dp))
            LinearProgressIndicator(
                progress = { (position.toFloat() / durationMs).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.size(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Caption(formatClock(position))
                Caption(formatClock(durationMs))
            }
        }

        outcome?.let {
            Spacer(Modifier.size(4.dp))
            Caption(it)
        }
    }
}

@Composable
private fun Cover(art: Bitmap?) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = "Album artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Which player to open in. Only players that are actually installed are offered,
 * and the choice can be remembered so this is asked once rather than every time.
 */
@Composable
private fun PlayerChooser(
    onDismiss: () -> Unit,
    onChosen: (packageName: String, remember: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var remember by remember { mutableStateOf(true) }
    val installed = Sources.packages.filter { PlayerCommands.isInstalled(context, it) }.distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play in") },
        text = {
            Column {
                if (installed.isEmpty()) {
                    Text(
                        text = "Neither Spotify nor YouTube Music is installed on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    installed.forEach { packageName ->
                        TextButton(onClick = { onChosen(packageName, remember) }) {
                            Text(Sources.label(packageName))
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = remember, onCheckedChange = { remember = it })
                        Text(
                            text = "Use this every time",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun livePosition(
    positionMs: Long,
    updatedAt: String,
    isPlaying: Boolean,
    nowMs: Long,
): Long {
    if (!isPlaying) return positionMs
    val takenAt = runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrNull()
        ?: return positionMs
    return positionMs + (nowMs - takenAt).coerceAtLeast(0)
}
