package com.museroom.app.ui.screens

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.museroom.app.media.Artwork
import com.museroom.app.media.Sources
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.ListenRepository
import com.museroom.app.sync.SyncEngine
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoProgress
import com.museroom.app.ui.kit.hardShadow
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.util.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** A screen title, in the display face with a hard coloured drop. */
@Composable
fun ScreenTitle(text: String, drop: Color = Neo.colors.violet) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text.uppercase(), style = bangers(32).copy(color = Neo.colors.ink))
        Box(
            Modifier
                .size(width = 26.dp, height = 7.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(drop),
        )
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) = Text(
    text = text,
    modifier = modifier,
    style = MaterialTheme.typography.bodySmall,
    color = Neo.colors.ink.copy(alpha = 0.65f),
)

/**
 * The sign-in panel, shown wherever a screen needs an account. Google is the
 * intended route; email exists so the app can be exercised without it.
 */
@Composable
fun SignInPanel(why: String) {
    val context = LocalContext.current
    val c = Neo.colors
    val auth = remember { AuthRepository.get(context) }
    val sync = remember { SyncEngine.get(context) }
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    NeoCard(radius = 20.dp, shadow = 6.dp, padding = 20.dp) {
        Text("Sign in to sync", style = MaterialTheme.typography.titleLarge, color = c.ink)
        Spacer(Modifier.size(5.dp))
        Note(why)
        Spacer(Modifier.size(16.dp))

        NeoButton(
            text = "Continue with Google",
            tone = NeoTone.Paper,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                message = null
                scope.launch {
                    auth.signInWithGoogle(context)
                        .onSuccess { sync.sync() }
                        .onFailure { message = it.message }
                    busy = false
                }
            },
        )

        Spacer(Modifier.size(16.dp))
        Field(email, { email = it }, "Email")
        Spacer(Modifier.size(10.dp))
        Field(password, { password = it }, "Password", secret = true)
        Spacer(Modifier.size(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeoButton("Sign in", small = true, enabled = !busy, onClick = {
                busy = true; message = null
                scope.launch {
                    auth.signInWithPassword(email, password)
                        .onSuccess { sync.sync() }
                        .onFailure { message = it.message }
                    busy = false
                }
            })
            NeoButton("Create", small = true, tone = NeoTone.Paper, enabled = !busy, onClick = {
                busy = true; message = null
                scope.launch {
                    auth.signUpWithPassword(email, password)
                        .onSuccess { message = "Check your email, then sign in." }
                        .onFailure { message = it.message }
                    busy = false
                }
            })
        }

        message?.let {
            Spacer(Modifier.size(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.pink)
        }
    }
}

/**
 * An input with the same 3px stroke and hard shadow as everything else. Material
 * draws a hairline outline, which beside these borders looks like a mistake.
 */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .hardShadow(3.dp, c.ink, shape)
            .clip(shape)
            .background(c.card)
            .border(3.dp, c.ink, shape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
            cursorBrush = SolidColor(c.violet),
            visualTransformation = if (secret) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.ink.copy(alpha = 0.42f),
                    )
                }
                inner()
            },
        )
    }
}

/**
 * One person and what they are playing, shared by the friends and nearby lists.
 *
 * Artwork is looked up by track name on this phone rather than shared from
 * theirs, and the bar extrapolates locally so a snapshot every fifteen seconds
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
    hostId: String? = null,
    fingerprint: String = "",
    tint: Color = Neo.colors.violet,
) {
    val context = LocalContext.current
    val c = Neo.colors
    val scope = rememberCoroutineScope()
    val listen = remember { ListenRepository.get(context) }

    var art by remember(title, artist) { mutableStateOf<Bitmap?>(Artwork.cached(title, artist)) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var asked by remember(title) { mutableStateOf(false) }
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

    NeoCard(radius = 16.dp, shadow = 4.dp, padding = 13.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint)
                    .border(3.dp, c.onAccent, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                art?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("@$handle", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Label(if (isPlaying) "listening" else "quiet", color = c.ink)
                }
                Text(
                    title.ifBlank { "Nothing shared" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    artist.ifBlank { "Unknown artist" },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.ink.copy(alpha = 0.62f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (durationMs > 0 && title.isNotBlank()) {
            val position = livePosition(positionMs, updatedAt, isPlaying, nowMs).coerceIn(0L, durationMs)
            Spacer(Modifier.size(10.dp))
            NeoProgress(position.toFloat() / durationMs, fill = tint)
            Spacer(Modifier.size(7.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText("${formatClock(position)} / ${formatClock(durationMs)}", size = 11, color = c.ink)
                // Asking first, rather than launching straight into another app.
                // The host is told; playback starts when they say yes.
                NeoButton(
                    text = if (asked) "Asked" else "Ask to join",
                    small = true,
                    enabled = !asked && hostId != null,
                    onClick = {
                        val host = hostId ?: return@NeoButton
                        asked = true
                        outcome = "Asking @$handle…"
                        scope.launch {
                            listen.ask(host, title, artist, fingerprint, sourceTrackId)
                                .onSuccess { outcome = "Asked @$handle. You will hear back on Now." }
                                .onFailure { asked = false; outcome = it.message }
                        }
                    },
                )
            }
        }

        outcome?.let {
            Spacer(Modifier.size(6.dp))
            Note(it)
        }
    }
}

private fun livePosition(positionMs: Long, updatedAt: String, isPlaying: Boolean, nowMs: Long): Long {
    if (!isPlaying) return positionMs
    val takenAt = runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrNull() ?: return positionMs
    return positionMs + (nowMs - takenAt).coerceAtLeast(0)
}
