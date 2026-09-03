package com.museroom.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.media.Avatars
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.ProfileRepository
import com.museroom.app.net.Visibility
import com.museroom.app.privacy.PrivacyState
import com.museroom.app.proximity.ProximityManager
import com.museroom.app.sync.SyncEngine
import com.museroom.app.sync.SyncState
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoPill
import com.museroom.app.ui.kit.NeoSwitch
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.util.formatAgo
import kotlinx.coroutines.launch

/**
 * What each installed player will let Museroom do to it.
 *
 * Following somebody rests entirely on seeking, and whether a player accepts one
 * is a fact about that player rather than something to assume. Shown here so a
 * failure to follow has an explanation on screen.
 */
@Composable
fun YouScreen() {
    val context = LocalContext.current
    val c = Neo.colors
    val scope = rememberCoroutineScope()

    val auth = remember { AuthRepository.get(context) }
    val profiles = remember { ProfileRepository.get(context) }
    val privacy = remember { PrivacyState.get(context) }
    val sync = remember { SyncEngine.get(context) }
    val theme = remember { com.museroom.app.ui.ThemeState.get(context) }

    val session by auth.session.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle()
    val isPrivate by privacy.privateSession.collectAsStateWithLifecycle()
    val isDark by theme.dark.collectAsStateWithLifecycle()
    val syncState by sync.state.collectAsStateWithLifecycle()
    val waiting by sync.pendingEvents.collectAsStateWithLifecycle(0)

    var confirmWipe by remember { mutableStateOf(false) }
    var myFace by remember(profile?.avatarUrl) { mutableStateOf(Avatars.cached(profile?.avatarUrl)) }
    var photoNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile?.avatarUrl) {
        if (myFace == null) myFace = Avatars.fetch(profile?.avatarUrl)
    }

    // The system picker, so Museroom never asks for access to the gallery: it
    // is handed one picture and sees nothing else.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        photoNote = "Uploading"
        scope.launch {
            val bytes = Avatars.encode(context, uri)
            if (bytes == null) {
                photoNote = "That picture could not be read."
                return@launch
            }
            profiles.setAvatar(bytes)
                .onSuccess { photoNote = null; myFace = null }
                .onFailure { photoNote = it.message }
        }
    }

    LaunchedEffect(session?.userId) { if (session != null) profiles.refresh() }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = c.card,
            title = { Text("Delete everything?", style = bangers(26).copy(color = c.ink)) },
            text = {
                Note("Every track, every event and your minutes go, from this phone and from the server. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { sync.deleteAllHistory() }
                    confirmWipe = false
                }) { Text("DELETE", color = c.pink, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text("CANCEL", color = c.ink, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("You")

        // Private mode sits first, because an off switch nobody can find is not one.
        NeoAccentCard(fill = c.pink, radius = 18.dp, shadow = 6.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Private session",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                NeoSwitch(checked = isPrivate, onCheckedChange = { on ->
                    privacy.setPrivate(on)
                    if (on) ProximityManager.get(context).stop()
                })
            }
        }

        if (session == null) {
            SignInPanel("Sign in for a handle, friends and the board.")
        } else {
            NeoCard(radius = 18.dp, padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Box(
                        Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.violet)
                            .border(3.dp, c.onAccent, RoundedCornerShape(16.dp))
                            .clickable { pickPhoto.launch(PickVisualMediaRequest(ImageOnly)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val face = myFace
                        if (face != null) {
                            Image(
                                face.asImageBitmap(), null, Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Text(
                                profile?.handle.orEmpty().take(1).uppercase().ifBlank { "?" },
                                style = bangers(28).copy(color = Color.White),
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile?.handle ?: "Loading",
                            style = MaterialTheme.typography.titleMedium, color = c.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Note(
                            when (val s = syncState) {
                                is SyncState.Synced ->
                                    if (s.rows == 0) "Up to date, ${formatAgo(s.atMs)}"
                                    else "Sent ${s.rows} rows, ${formatAgo(s.atMs)}"
                                is SyncState.Failed -> "Last sync failed: ${s.reason}"
                                is SyncState.Running -> "Uploading"
                                else -> "$waiting events waiting"
                            },
                        )
                    }
                    NeoButton("Sync", small = true, tone = NeoTone.Paper, onClick = {
                        scope.launch { sync.sync() }
                    })
                }
            }

            Label("Who sees what you play", color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Visibility.entries.forEach { v ->
                    NeoButton(
                        text = v.key,
                        small = true,
                        tone = if (profile?.who == v) NeoTone.Violet else NeoTone.Paper,
                        onClick = { scope.launch { profiles.setVisibility(v) } },
                    )
                }
            }

            NeoCard(radius = 14.dp, shadow = 3.dp, padding = 14.dp) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Show me on the global board",
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.ink, modifier = Modifier.weight(1f),
                    )
                    NeoSwitch(
                        checked = profile?.onGlobalBoard ?: true,
                        onCheckedChange = { on -> scope.launch { profiles.setOnGlobalBoard(on) } },
                    )
                }
            }
        }

        NeoCard(radius = 14.dp, shadow = 3.dp, padding = 14.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Dark theme",
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.ink, modifier = Modifier.weight(1f),
                )
                NeoSwitch(checked = isDark, onCheckedChange = theme::setDark)
            }
        }

        if (session != null) {
            photoNote?.let { Note(it) }

            Label("Username", color = c.ink)
            HandlePicker()

            Label("Who can join your room", color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeoButton(
                    "Ask first",
                    small = true,
                    tone = if (profile?.openToAll == false) NeoTone.Violet else NeoTone.Paper,
                    onClick = { scope.launch { profiles.setOpenToAll(false) } },
                )
                NeoButton(
                    "Anyone",
                    small = true,
                    tone = if (profile?.openToAll == true) NeoTone.Violet else NeoTone.Paper,
                    onClick = { scope.launch { profiles.setOpenToAll(true) } },
                )
            }
        }

        Label("Counted", color = c.ink)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeoPill("Spotify", fill = c.lime, accent = true)
            NeoPill("YouTube Music", fill = c.lime, accent = true)
        }

        Spacer(Modifier.size(6.dp))
        NeoButton(
            "Delete all history",
            tone = NeoTone.Paper,
            modifier = Modifier.fillMaxWidth(),
            onClick = { confirmWipe = true },
        )
        if (session != null) {
            NeoButton(
                "Sign out",
                tone = NeoTone.Paper,
                small = true,
                onClick = { auth.signOut() },
            )
        }
    }
}

/**
 * Your name here, chosen rather than inherited.
 *
 * Everything anybody else sees of you is this word: the leaderboard, a friend
 * search, a request to listen along. It used to be built out of your email
 * address, which is not a thing anybody should discover about themselves on a
 * public board.
 */
@Composable
private fun HandlePicker() {
    val context = LocalContext.current
    val c = Neo.colors
    val scope = rememberCoroutineScope()
    val profiles = remember { ProfileRepository.get(context) }
    val profile by profiles.profile.collectAsStateWithLifecycle()

    var draft by remember(profile?.handle) { mutableStateOf(profile?.handle.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val changed = draft.trim().lowercase() != profile?.handle?.lowercase()

    NeoCard(radius = 16.dp, padding = 14.dp) {
        Field(draft, { draft = it.trim(); message = null }, "Username")
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NeoButton(
                text = if (saving) "Saving" else "Save",
                small = true,
                enabled = changed && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        profiles.setHandle(draft)
                            .onSuccess { message = "Saved." }
                            .onFailure { message = it.message }
                        saving = false
                    }
                },
            )
            message?.let { Note(it) }
        }
    }
}
