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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.museroom.app.media.Sources
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.ProfileRepository
import com.museroom.app.net.Updates
import com.museroom.app.net.SafetyRepository
import com.museroom.app.net.Visibility
import com.museroom.app.notify.FriendAlerts
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
import com.museroom.app.util.StayAwake
import com.museroom.app.util.formatAgo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What each installed player will let Museroom do to it.
 *
 * Following somebody rests entirely on seeking, and whether a player accepts one
 * is a fact about that player rather than something to assume. Shown here so a
 * failure to follow has an explanation on screen.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    val alerts = remember { FriendAlerts.get(context) }

    val session by auth.session.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle()
    val isPrivate by privacy.privateSession.collectAsStateWithLifecycle()
    val isDark by theme.dark.collectAsStateWithLifecycle()
    val friendAlertsOn by alerts.enabled.collectAsStateWithLifecycle()
    val syncState by sync.state.collectAsStateWithLifecycle()
    val waiting by sync.pendingEvents.collectAsStateWithLifecycle(0)

    var confirmWipe by remember { mutableStateOf(false) }
    var explainPrivate by remember { mutableStateOf(false) }
    var showTour by remember { mutableStateOf(false) }
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    var photoNote by remember { mutableStateOf<String?>(null) }
    var updateNote by remember { mutableStateOf<String?>(null) }

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
                .onSuccess { photoNote = null }
                .onFailure { photoNote = it.message }
        }
    }

    LaunchedEffect(session?.userId) { if (session != null) profiles.refresh() }

    if (confirmDeleteAccount) {
        ConfirmDialog(
            title = "Delete your account?",
            body = "Your username, picture, minutes, history, friends and every " +
                "row about you are deleted from this phone and from the server, " +
                "and the account itself goes with them. This cannot be undone.",
            confirm = "DELETE ACCOUNT",
            destructive = true,
            onConfirm = {
                confirmDeleteAccount = false
                scope.launch {
                    SafetyRepository.get(context).deleteAccount()
                        .onSuccess {
                            sync.deleteAllHistory()
                            auth.signOut()
                        }
                        .onFailure { photoNote = it.message }
                }
            },
            onDismiss = { confirmDeleteAccount = false },
        )
    }

    if (showTour) {
        FeatureTour(onDismiss = { showTour = false })
    }

    if (explainPrivate) {
        AlertDialog(
            onDismissRequest = { explainPrivate = false },
            containerColor = c.card,
            title = { Text("Private session", style = bangers(26).copy(color = c.ink)) },
            text = {
                Column {
                    Note(
                        "While it is on, Museroom records nothing at all. No track is " +
                            "written down, no minutes are counted towards your total or the " +
                            "board, and nobody can see what you are playing.",
                    )
                    Spacer(Modifier.size(10.dp))
                    Note(
                        "Nearby is switched off with it, so your phone stops broadcasting " +
                            "to the people around you as well. Everything already recorded " +
                            "stays exactly as it was.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { explainPrivate = false }) {
                    Text("GOT IT", color = c.ink, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }

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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Private session",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                // The switch turns off more than it sounds like it does, and
                // what it turns off is the whole point of the app. Worth a
                // tap to find out before you flip it, not a paragraph nobody
                // reads sitting underneath it forever.
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(c.onAccent.copy(alpha = 0.14f))
                        .border(2.dp, c.onAccent, CircleShape)
                        .clickable { explainPrivate = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "i",
                        style = bangers(15).copy(color = c.onAccent),
                    )
                }
                Spacer(Modifier.weight(1f))
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
                    // A tappable thing that looks exactly like a non-tappable
                    // thing is not tappable as far as anybody knows. The badge
                    // is the only reason anyone would find out a picture can
                    // go here, so it stays until there is one.
                    Box {
                        Face(
                            profile?.handle.orEmpty(),
                            profile?.avatarUrl,
                            62.dp,
                            modifier = Modifier.clickable {
                                pickPhoto.launch(PickVisualMediaRequest(ImageOnly))
                            },
                            shape = RoundedCornerShape(18.dp),
                        )
                        if (profile?.avatarUrl.isNullOrBlank()) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 5.dp, y = 5.dp)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(c.lime)
                                    .border(2.5.dp, c.onAccent, CircleShape)
                                    .clickable {
                                        pickPhoto.launch(PickVisualMediaRequest(ImageOnly))
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("+", style = bangers(17).copy(color = c.onAccent))
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(
                                profile?.handle ?: "Loading",
                                style = MaterialTheme.typography.titleMedium, color = c.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // The one number here somebody else decided, so it
                            // belongs beside the name rather than buried with
                            // the settings.
                            profile?.likesReceived?.takeIf { it > 0 }?.let { count ->
                                NeoPill("$count liked", fill = c.pink)
                            }
                        }
                        if (profile?.avatarUrl.isNullOrBlank()) {
                            Note("Tap the square to add a photo")
                        }
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

        // The only notification here that answers no question. Pleasant with
        // three friends, a phone that buzzes all evening with thirty.
        NeoCard(radius = 14.dp, shadow = 3.dp, padding = 14.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Tell me when friends listen",
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.ink,
                    )
                    Note("Silent. Mute one friend from the Friends tab.")
                }
                NeoSwitch(checked = friendAlertsOn, onCheckedChange = alerts::setEnabled)
            }
        }

        HistorySection()

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

        if (!StayAwake.isExempt(context)) {
            Label("Keep counting", color = c.ink)
            NeoAccentCard(fill = c.pink, radius = 16.dp, padding = 14.dp) {
                Text(
                    "Android may stop Museroom in the background",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "Minutes go uncounted when it does, with nothing on screen to say so.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Spacer(Modifier.size(10.dp))
                NeoButton(
                    "Let it keep running",
                    small = true,
                    tone = NeoTone.Paper,
                    onClick = { StayAwake.ask(context) },
                )
            }
        }

        NeoButton(
            "What's in Museroom",
            tone = NeoTone.Paper,
            modifier = Modifier.fillMaxWidth(),
            onClick = { showTour = true },
        )

        // Nothing here updates itself, so there has to be somewhere to ask.
        NeoButton(
            updateNote ?: "Check for an update",
            tone = NeoTone.Paper,
            enabled = updateNote == null,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                updateNote = "Looking"
                scope.launch {
                    Updates.check(context, force = true)
                        .onSuccess { found ->
                            updateNote = if (found == null) {
                                "You are on the newest build"
                            } else {
                                "Museroom ${found.versionName} is out — see the Now tab"
                            }
                        }
                        .onFailure { updateNote = "Could not reach the site" }
                    delay(4_000)
                    updateNote = null
                }
            },
        )

        Label("Counted", color = c.ink)

        // Read from the list itself rather than typed out here, so this can
        // never quietly disagree with what is actually being counted.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sources.labels.forEach { NeoPill(it, fill = c.lime, accent = true) }
        }
        Spacer(Modifier.size(2.dp))
        Note("Nothing else on your phone is read. No browsers, video or podcast apps.")

        if (session != null) {
            BlockedList()
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
                "Delete my account",
                tone = NeoTone.Paper,
                modifier = Modifier.fillMaxWidth(),
                onClick = { confirmDeleteAccount = true },
            )
        }

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

/**
 * Who you have blocked, and the way back.
 *
 * A block that cannot be undone is a trap rather than a tool, and the list has
 * to live somewhere findable or the only way to undo one would be to remember
 * the username and hope. It stays out of the way entirely when it is empty.
 */
@Composable
private fun BlockedList() {
    val context = LocalContext.current
    val c = Neo.colors
    val scope = rememberCoroutineScope()
    val safety = remember { SafetyRepository.get(context) }
    val blocked by safety.blocked.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { safety.refresh() }
    if (blocked.isEmpty()) return

    Label("Blocked", color = c.ink)
    blocked.forEach { person ->
        NeoCard(radius = 14.dp, shadow = 3.dp, padding = 12.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Face(person.handle, person.avatarUrl, 34.dp)
                Text(
                    person.handle,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                NeoButton("Unblock", small = true, tone = NeoTone.Paper, onClick = {
                    scope.launch { safety.unblock(person.userId) }
                })
            }
        }
    }
}
