package com.museroom.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.net.LikesRepository
import com.museroom.app.net.RequestsRepository
import com.museroom.app.net.Updates
import com.museroom.app.notify.Notifier
import com.museroom.app.sync.FollowSession
import com.museroom.app.sync.RoomPresence
import com.museroom.app.tracking.PlaybackTracker
import com.museroom.app.ui.kit.NeoDot
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.halftone
import com.museroom.app.ui.kit.hardShadow
import com.museroom.app.ui.screens.AccessGate
import com.museroom.app.ui.screens.AlbumScreen
import com.museroom.app.ui.screens.ArtistScreen
import com.museroom.app.ui.screens.BoardScreen
import com.museroom.app.ui.screens.FeatureTour
import com.museroom.app.ui.screens.HistoryScreen
import com.museroom.app.ui.screens.LibraryScreen
import com.museroom.app.ui.screens.ListingScreen
import com.museroom.app.ui.screens.LyricsScreen
import com.museroom.app.ui.screens.MiniPlayer
import com.museroom.app.ui.screens.NowScreen
import com.museroom.app.ui.screens.OnboardingScreen
import com.museroom.app.ui.screens.PersonCard
import com.museroom.app.ui.screens.PlayerScreen
import com.museroom.app.ui.screens.PlaylistScreen
import com.museroom.app.ui.screens.QueueScreen
import com.museroom.app.ui.screens.RequestsScreen
import com.museroom.app.ui.screens.RoomScreen
import com.museroom.app.ui.screens.RoomsScreen
import com.museroom.app.ui.screens.SearchScreen
import com.museroom.app.ui.screens.SoundScreen
import com.museroom.app.ui.screens.TourState
import com.museroom.app.ui.screens.YouScreen
import com.museroom.app.util.NotificationAccess

/**
 * Five places, as the design has them.
 *
 * Friends and Nearby were never two ideas — both are people you could be
 * listening with — so they are two chips of one Rooms tab, which is what freed
 * the sticker Library needed. Nothing was removed; one of them stopped being a
 * destination and became a filter.
 *
 * The top bar belongs to each screen rather than to the shell. Every artboard
 * carries a different one: Home has the wordmark, Library has a crumb and a
 * plus, Board has the week it is showing. A single bar drawn above all five
 * would have to be the union of those, which is none of them.
 */

/** Somewhere you were pushed to, rather than a place the rail knows about. */
sealed interface Browse {
    data class Album(val id: String) : Browse
    data class Artist(val id: String) : Browse
    data class Playlist(val id: Long) : Browse

    /**
     * Somebody else's playlist, which is a browse id rather than a row here.
     *
     * Kept apart from [Playlist] rather than folded into it with a nullable
     * field. One is a list on this phone that can be renamed and reordered, the
     * other is a page on YouTube's side that can only be played, and a screen
     * that had to check which it was on every button would get it wrong once.
     */
    data class Listing(val id: String) : Browse

    data object Queue : Browse
    data object Sound : Browse
    data object History : Browse

    /**
     * The room, as a place rather than a card.
     *
     * It used to be three cards stacked on Home — who is in the room, what mode
     * it is in, what is playing — which meant the one thing you are doing was
     * competing with the shelves for the same column. A room is somewhere you
     * are, so it gets a screen, and like the player it covers the rail while
     * you are in it.
     */
    data object Room : Browse
}

/** The pushed surfaces that cover the rail, because they are not a tab. */
private fun Browse.isFull(): Boolean =
    this is Browse.Queue || this is Browse.Room

enum class Tab(val label: String, val icon: String) {
    Now("Home", NeoIcons.Home),
    Library("Library", NeoIcons.Library),
    Rooms("Rooms", NeoIcons.Nearby),
    Board("Board", NeoIcons.Board),
    You("You", NeoIcons.You),
}

@Composable
fun MuseroomApp() {
    val granted = rememberAccessGranted()
    val appContext = LocalContext.current
    // Asked once and remembered. Somebody who chose to get on without the
    // permission should not be asked again every time they open the app.
    var skipped by remember { mutableStateOf(AccessGate.skipped(appContext)) }
    var tab by remember { mutableStateOf(Tab.Now) }

    // Being let into somebody's room starts the music by itself, so the screen
    // that shows the room should come to meet it. Otherwise the first sign of
    // a room is sound with no picture.
    val following by FollowSession.following.collectAsStateWithLifecycle()

    // Not a tab. Requests are somewhere you go and come back from, and giving
    // them a sixth sticker on the rail would put a thing you visit twice a week
    // beside the four you live on.
    var requestsOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = requestsOpen) { requestsOpen = false }

    // Neither is a tab either, and for the same reason. Search is somewhere you
    // go with a question and leave with an answer, and the player is one track
    // rather than one of the five places the app lives.
    var searchOpen by remember { mutableStateOf(false) }
    var playerOpen by remember { mutableStateOf(false) }
    var lyricsOpen by remember { mutableStateOf(false) }

    /**
     * A record or a person, pushed over whatever tab you were on.
     *
     * A list rather than a single page, because the way round these is
     * artist to album to artist again, and back should retrace that rather
     * than dumping you at the tab you started from.
     */
    var trail by remember { mutableStateOf(listOf<Browse>()) }
    val here = trail.lastOrNull()

    // Registered innermost-last, because the handler registered last is the
    // one Compose asks first. Back used to close the player from underneath
    // the lyrics, which left the words on screen over nothing.
    BackHandler(enabled = here != null) { trail = trail.dropLast(1) }
    BackHandler(enabled = searchOpen) { searchOpen = false }
    BackHandler(enabled = playerOpen && !lyricsOpen) { playerOpen = false }
    BackHandler(enabled = lyricsOpen) { lyricsOpen = false }

    LaunchedEffect(following?.hostId) {
        if (following != null) {
            requestsOpen = false
            searchOpen = false
            playerOpen = false
            trail = listOf(Browse.Room)
        }
    }
    val c = Neo.colors

    Box(
        Modifier
            .fillMaxSize()
            .background(c.paper)
            .halftone(c.ink, alpha = if (c.dark) 0.10f else 0.07f),
    ) {
        if (!granted && !skipped) {
            OnboardingScreen(onSkip = { skipped = true })
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

        // Presence, the requests inbox and this phone's likes, started once
        // for the whole app rather than by whichever screen happens to be
        // drawn first.
        LaunchedEffect(context) {
            RoomPresence.start(context)
            RequestsRepository.get(context).start()
            LikesRepository.get(context).refresh()
        }

        val covered = searchOpen || (here?.isFull() == true)

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    here is Browse.Album -> AlbumScreen(
                        browseId = here.id,
                        onBack = { trail = trail.dropLast(1) },
                        onOpenArtist = { trail = trail + Browse.Artist(it) },
                    )
                    here is Browse.Queue -> QueueScreen(onBack = { trail = trail.dropLast(1) })
                    here is Browse.Room -> RoomScreen(
                        onClose = { trail = trail.dropLast(1) },
                        onOpenQueue = { trail = trail + Browse.Queue },
                    )
                    here is Browse.Sound -> SoundScreen(onBack = { trail = trail.dropLast(1) })
                    here is Browse.History -> HistoryScreen(onBack = { trail = trail.dropLast(1) })
                    here is Browse.Playlist -> PlaylistScreen(
                        id = here.id,
                        onBack = { trail = trail.dropLast(1) },
                    )
                    here is Browse.Artist -> ArtistScreen(
                        browseId = here.id,
                        onBack = { trail = trail.dropLast(1) },
                        onOpenAlbum = { trail = trail + Browse.Album(it) },
                    )
                    here is Browse.Listing -> ListingScreen(
                        browseId = here.id,
                        onBack = { trail = trail.dropLast(1) },
                        onOpenArtist = { trail = trail + Browse.Artist(it) },
                    )
                    searchOpen -> SearchScreen(
                        onClose = { searchOpen = false },
                        onOpenArtist = { searchOpen = false; trail = listOf(Browse.Artist(it)) },
                        onOpenAlbum = { searchOpen = false; trail = listOf(Browse.Album(it)) },
                        onOpenPlaylist = { searchOpen = false; trail = listOf(Browse.Listing(it)) },
                    )
                    requestsOpen -> RequestsScreen()
                    else -> when (tab) {
                        Tab.Now -> NowScreen(
                            onOpenPlayer = { playerOpen = true },
                            onOpenSearch = { searchOpen = true },
                            onOpenHistory = { trail = listOf(Browse.History) },
                            onOpenRoom = { trail = listOf(Browse.Room) },
                            onOpenRooms = { tab = Tab.Rooms },
                        )
                        Tab.Library -> LibraryScreen(
                            onOpenSearch = { searchOpen = true },
                            onOpenPlaylist = { trail = listOf(Browse.Playlist(it)) },
                            onOpenAlbum = { trail = listOf(Browse.Album(it)) },
                            onOpenArtist = { trail = listOf(Browse.Artist(it)) },
                        )
                        Tab.Rooms -> RoomsScreen(onOpenRoom = { trail = listOf(Browse.Room) })
                        Tab.Board -> BoardScreen()
                        Tab.You -> YouScreen(
                            onOpenSound = { trail = listOf(Browse.Sound) },
                            onOpenHistory = { trail = listOf(Browse.History) },
                        )
                    }
                }
            }
            // The mini player and the rail are one object: the bar sits on the
            // nav rather than floating over the list, because a bar that hovers
            // hides the last row of every screen it appears on.
            if (!covered) {
                MiniPlayer(onOpen = { playerOpen = true })
                BottomNav(tab) {
                    tab = it
                    requestsOpen = false
                    searchOpen = false
                    trail = emptyList()
                }
            }
        }

        // Over everything, including the person card, because while it is up it
        // is the only thing being looked at.
        if (playerOpen) {
            // The fill goes to the edges and the padding goes inside it. Insetting
            // the whole sheet leaves a strip of whatever was underneath showing
            // along the top, which reads as the screen not having opened.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.paper)
                    .halftone(c.ink, alpha = if (c.dark) 0.10f else 0.07f),
            ) {
                PlayerScreen(
                    onClose = { playerOpen = false },
                    onOpenLyrics = { lyricsOpen = true },
                    onOpenArtist = {
                        playerOpen = false
                        searchOpen = false
                        trail = listOf(Browse.Artist(it))
                    },
                    onOpenQueue = {
                        playerOpen = false
                        trail = trail + Browse.Queue
                    },
                    onStartedRoom = {
                        playerOpen = false
                        trail = listOf(Browse.Room)
                    },
                )
            }
        }

        if (lyricsOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.paper)
                    .halftone(c.ink, alpha = if (c.dark) 0.10f else 0.07f),
            ) {
                LyricsScreen(onClose = { lyricsOpen = false })
            }
        }
    }
}

/**
 * A chunky sticker rail. The selected tab is a lime sticker, so it keeps its dark
 * ink whichever theme is on.
 */
@Composable
private fun BottomNav(current: Tab, onPick: (Tab) -> Unit) {
    val c = Neo.colors
    // The only thing in here worth a mark. A newer build is a standing fact
    // rather than a message, so it wants a dot on the way in rather than
    // something that has to be read and dismissed.
    val newer by Updates.newer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requests = remember { RequestsRepository.get(context) }
    val waiting by requests.count.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().background(c.card)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(c.ink))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 6.dp,
                    end = 6.dp,
                    top = 10.dp,
                    bottom = 10.dp + WindowInsets.navigationBars
                        .asPaddingValues()
                        .calculateBottomPadding(),
                ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { entry ->
                val on = entry == current
                val shape = RoundedCornerShape(13.dp)
                val lift by animateDpAsState(if (on) 3.dp else 0.dp, tween(140), label = "lift")
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (on) {
                                Modifier
                                    .hardShadow(lift, c.onAccent, shape)
                                    .clip(shape)
                                    .background(c.lime)
                                    .border(2.5.dp, c.onAccent, shape)
                            } else {
                                Modifier.clip(shape)
                            }
                        )
                        .tap { onPick(entry) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box {
                        NeoIcon(
                            entry.icon,
                            size = 22.dp,
                            color = if (on) c.onAccent else c.ink.copy(alpha = 0.72f),
                        )
                        val marked = (entry == Tab.You && newer != null) ||
                            (entry == Tab.Rooms && waiting > 0)
                        if (marked) {
                            NeoDot(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-3).dp),
                                ring = if (on) c.lime else c.card,
                            )
                        }
                    }
                    Text(
                        text = entry.label.uppercase(),
                        style = TextStyle(
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
