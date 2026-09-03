package com.museroom.app.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.media.PlayerCommands
import com.museroom.app.media.Sources
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
private fun PlayerControlReport() {
    val context = LocalContext.current
    val c = Neo.colors
    val caps = remember { PlayerCommands.capabilities(context).filter { it.installed } }

    if (caps.isEmpty()) {
        Note("No supported player installed.")
        return
    }
    NeoCard(radius = 14.dp, shadow = 3.dp, padding = 14.dp) {
        if (!com.museroom.app.media.TrackResolver.configured) {
            MonoText("exact track links: not configured", size = 11, color = c.ink)
            Spacer(Modifier.size(4.dp))
        }
        caps.forEach { cap ->
            MonoText(
                Sources.label(cap.packageName) + ": " + when {
                    !cap.hasLiveSession -> "idle, unknown until it plays"
                    cap.canSeek && cap.canPlayFromSearch -> "can follow and start songs"
                    cap.canSeek -> "can follow"
                    else -> "will not take a seek"
                },
                size = 11, color = c.ink,
            )
        }
    }
}

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
                            .border(3.dp, c.onAccent, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (profile?.handle ?: session?.email.orEmpty()).take(1).uppercase(),
                            style = bangers(28).copy(color = Color.White),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile?.handle?.let { "@$it" } ?: session?.email.orEmpty(),
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

        Label("Player control", color = c.ink)
        PlayerControlReport()

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
