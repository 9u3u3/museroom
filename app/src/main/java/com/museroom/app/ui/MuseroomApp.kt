package com.museroom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.tracking.PlaybackTracker
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MuseroomMark
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.halftone
import com.museroom.app.ui.screens.BoardScreen
import com.museroom.app.ui.screens.FriendsScreen
import com.museroom.app.ui.screens.NearbyScreen
import com.museroom.app.ui.screens.NowScreen
import com.museroom.app.ui.screens.OnboardingScreen
import com.museroom.app.ui.screens.YouScreen
import com.museroom.app.util.NotificationAccess

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

        Column(Modifier.fillMaxSize()) {
            TopBar(tab)
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
private fun TopBar(tab: Tab) {
    val c = Neo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
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
        Label(tab.label, color = c.ink)
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
