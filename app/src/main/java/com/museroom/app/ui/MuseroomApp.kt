package com.museroom.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.BoardPeriod
import com.museroom.app.net.BoardRepository
import com.museroom.app.net.LikesRepository
import com.museroom.app.notify.Notifier
import com.museroom.app.sync.FollowSession
import com.museroom.app.sync.RoomPresence
import com.museroom.app.tracking.PlaybackTracker
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MuseroomMark
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.halftone
import com.museroom.app.ui.screens.BoardScreen
import com.museroom.app.ui.screens.FeatureTour
import com.museroom.app.ui.screens.FriendsScreen
import com.museroom.app.ui.screens.NearbyScreen
import com.museroom.app.ui.screens.NowScreen
import com.museroom.app.ui.screens.OnboardingScreen
import com.museroom.app.ui.screens.PersonCard
import com.museroom.app.ui.screens.TourState
import com.museroom.app.ui.screens.YouScreen
import com.museroom.app.util.NotificationAccess
import com.museroom.app.util.formatMinutes
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

enum class Tab(val label: String, val icon: String) {
    Now("Now", NeoIcons.Now),
    Friends("Friends", NeoIcons.Friends),
    Nearby("Nearby", NeoIcons.Nearby),
    Board("Board", NeoIcons.Board),
    You("You", NeoIcons.You),
}

@Composable
fun MuseroomApp() {
    val granted = rememberAccessGranted()
    var tab by remember { mutableStateOf(Tab.Now) }

    // Being let into somebody's room starts the music by itself, so the screen
    // that shows the room should come to meet it. Otherwise the first sign of
    // a room is sound with no picture.
    val following by FollowSession.following.collectAsStateWithLifecycle()
    LaunchedEffect(following?.hostId) {
        if (following != null) tab = Tab.Now
    }
    val c = Neo.colors

    Box(
        Modifier
            .fillMaxSize()
            .background(c.paper)
            .halftone(c.ink, alpha = if (c.dark) 0.10f else 0.07f),
    ) {
        if (!granted) {
            OnboardingScreen()
            return@Box
        }

        AskForNotifications()

        // Once, on the way in. Most of what this app does only happens if
        // somebody switches it on, and none of that is discoverable by
        // pressing around a screen showing one song.
        val context = LocalContext.current
        var showTour by remember { mutableStateOf(!TourState.seen(context)) }
        if (showTour) {
            FeatureTour(onDismiss = {
                TourState.markSeen(context)
                showTour = false
            })
        }

        // Drawn once, above everything, because a name is tappable on five
        // different screens and each of them wants the same page.
        PersonCard()

        Column(Modifier.fillMaxSize()) {
            TopBar()
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.Now -> NowScreen()
                    Tab.Friends -> FriendsScreen()
                    Tab.Nearby -> NearbyScreen()
                    Tab.Board -> BoardScreen()
                    Tab.You -> YouScreen()
                }
            }
            BottomNav(tab) { tab = it }
        }
    }
}

@Composable
private fun TopBar() {
    val c = Neo.colors
    val context = LocalContext.current
    val auth = remember { AuthRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()

    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(c.violet)
                .border(2.5.dp, c.onAccent, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            MuseroomMark(size = 24.dp, note = c.onAccent, ghost = c.lime)
        }
        Text(
            text = "MUSEROOM",
            style = bangers(26).copy(color = c.ink),
        )
        Spacer(Modifier.weight(1f))
        HeaderStats(signedIn = session != null)
    }
}

/**
 * Today's minutes, rank and who's listening along, at a glance, on every
 * screen. It used to take scrolling past a full-bleed album cover to find any
 * of this, which meant it took scrolling past a full-bleed album cover to
 * have a reason to open the app again.
 */
@Composable
private fun HeaderStats(signedIn: Boolean) {
    val c = Neo.colors
    val context = LocalContext.current
    val dao = remember { MuseroomDatabase.get(context).dao() }
    val board = remember { BoardRepository.get(context) }
    val auth = remember { AuthRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()

    val startOfToday = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val todayMs by dao.creditedSince(startOfToday).collectAsStateWithLifecycle(0L)
    val todayTracks by dao.tracksSince(startOfToday).collectAsStateWithLifecycle(0)

    LaunchedEffect(context) { RoomPresence.start(context) }
    // What this phone has already liked, so a heart is filled the first time
    // a list is drawn rather than filling in a moment later.
    val likes = remember { LikesRepository.get(context) }
    LaunchedEffect(session?.userId) { if (session != null) likes.refresh() }
    val roomMembers by RoomPresence.members.collectAsStateWithLifecycle()

    // A rank a minute stale is still worth showing; nothing here needs the
    // fresh-to-the-second board read that the Board screen pays for itself.
    var rank by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(session?.userId) {
        rank = null
        while (session != null) {
            board.myRank(BoardPeriod.All).onSuccess { rank = it?.rank }
            delay(60_000)
        }
    }

    Column(horizontalAlignment = Alignment.End) {
        if (signedIn) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (roomMembers.isNotEmpty()) {
                    // The number worth opening the app to check: somebody is
                    // listening along with you right now.
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(c.pink)
                            .border(2.dp, c.onAccent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${roomMembers.size}",
                            style = TextStyle(
                                fontFamily = Archivo, fontWeight = FontWeight.W900,
                                fontSize = 10.sp, color = c.onAccent,
                            ),
                        )
                    }
                }
                rank?.let {
                    Text(
                        "#$it",
                        style = TextStyle(
                            fontFamily = Archivo, fontWeight = FontWeight.W900,
                            fontSize = 11.sp, color = c.onAccent,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(c.lime)
                            .border(2.dp, c.onAccent, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.size(3.dp))
        }
        Text(
            "${formatMinutes(todayMs)} · $todayTracks trk",
            style = TextStyle(
                fontFamily = Archivo, fontWeight = FontWeight.W800,
                fontSize = 11.sp, color = c.ink.copy(alpha = 0.75f),
            ),
        )
    }
}

/**
 * A chunky sticker rail. The selected tab is a lime sticker, so it keeps its dark
 * ink whichever theme is on.
 */
@Composable
private fun BottomNav(current: Tab, onPick: (Tab) -> Unit) {
    val c = Neo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.card)
            .border(width = 0.dp, color = c.card)
            .padding(top = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(c.ink),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.card)
                    .padding(
                        start = 8.dp,
                        end = 8.dp,
                        top = 9.dp,
                        bottom = 9.dp + WindowInsets.navigationBars
                            .asPaddingValues()
                            .calculateBottomPadding(),
                    ),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Tab.entries.forEach { entry ->
                    val on = entry == current
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(13.dp))
                            .then(
                                if (on) {
                                    Modifier
                                        .background(c.lime)
                                        .border(2.5.dp, c.onAccent, RoundedCornerShape(13.dp))
                                } else {
                                    Modifier
                                }
                            )
                            .tap { onPick(entry) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        NeoIcon(
                            entry.icon,
                            size = 21.dp,
                            color = if (on) c.onAccent else c.ink.copy(alpha = 0.72f),
                        )
                        Text(
                            text = entry.label.uppercase(),
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = Archivo,
                                fontWeight = FontWeight.W900,
                                fontSize = 9.sp,
                                letterSpacing = 0.9.sp,
                                color = if (on) c.onAccent else c.ink.copy(alpha = 0.72f),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** No ripple anywhere: the sticker itself is the feedback. */
private fun Modifier.tap(onClick: () -> Unit) = this.clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = onClick,
)

/**
 * Android 13 needs asking before we can post anything. Requested once, on the
 * way in, because the thing it is for is somebody wanting to listen with you and
 * that cannot wait for a settings screen.
 */
@Composable
private fun AskForNotifications() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        Notifier.ensureChannel(context)
        if (!Notifier.canPost(context)) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Notification access can only change outside the app, so re-read it on resume. */
@Composable
private fun rememberAccessGranted(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationAccess.isGranted(context)
                if (granted) {
                    NowPlayingRepository.start(context)
                    PlaybackTracker.start(context)
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}
