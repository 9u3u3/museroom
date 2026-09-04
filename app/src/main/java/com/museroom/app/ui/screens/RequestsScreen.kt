package com.museroom.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.net.AnsweredListenRequests
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.ListenRepository
import com.museroom.app.net.ListenRequest
import com.museroom.app.net.PendingRequest
import com.museroom.app.net.RequestsRepository
import com.museroom.app.notify.Notifier
import com.museroom.app.ui.Neo
import com.museroom.app.ui.Refreshing
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoTone
import kotlinx.coroutines.launch

/**
 * Everything waiting on an answer, on a page of its own.
 *
 * These were cards on the home screen, stacked above the music. Two kinds of
 * request, from two different features, pushing the thing the app is actually
 * for further down the page every time somebody asked for something. A request
 * is not news to be scrolled past; it is a question with two answers, and it
 * belongs somewhere you go on purpose.
 *
 * Nothing here polls on its own. The counts behind the dot and the lists on
 * this page are the same lists, so the page cannot disagree with the button
 * that opened it.
 */
@Composable
fun RequestsScreen() {
    val context = LocalContext.current
    val c = Neo.colors
    val auth = remember { AuthRepository.get(context) }
    val requests = remember { RequestsRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()

    val friendRequests by requests.friendRequests.collectAsStateWithLifecycle()
    val sent by requests.sentRequests.collectAsStateWithLifecycle()
    val inbox by requests.listenInbox.collectAsStateWithLifecycle()

    // Opening the page is a reason to look, on top of whatever the socket and
    // the slow poll are already doing.
    Refreshing(session?.userId, everyMs = 0) {
        if (session != null) requests.refresh()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Requests", drop = c.sky)

        if (session == null) {
            SignInPanel("Sign in to be asked things.")
            return@Column
        }

        if (friendRequests.isEmpty() && inbox.isEmpty() && sent.isEmpty()) {
            // Reachable with no dot on it, so it has to say something rather
            // than look broken.
            NeoCard { Note("Nothing is waiting on you.") }
            return@Column
        }

        if (inbox.isNotEmpty()) {
            Label("Asking to join", color = c.ink)
            inbox.forEach { RoomRequestCard(it) }
        }

        if (friendRequests.isNotEmpty()) {
            Label("Wants to be friends", color = c.ink)
            friendRequests.forEach { FriendRequestCard(it) }
        }

        if (sent.isNotEmpty()) {
            Label("You asked", color = c.ink)
            sent.forEach { SentRequestCard(it) }
        }
    }
}

/**
 * Somebody asking to listen along.
 *
 * The answer is written down here before it is sent, and the notification for
 * it is taken out of the shade at the same moment. A request can be answered
 * in two places and neither used to know about the other, so the card went on
 * asking a question that had been answered from the shade, and answering here
 * left the notification sitting there asking.
 */
@Composable
private fun RoomRequestCard(request: ListenRequest) {
    val context = LocalContext.current
    val c = Neo.colors
    val scope = rememberCoroutineScope()
    val listen = remember { ListenRepository.get(context) }
    val requests = remember { RequestsRepository.get(context) }
    var busy by remember(request.id) { mutableStateOf(false) }

    fun answer(accept: Boolean) {
        busy = true
        AnsweredListenRequests.mark(request.id)
        Notifier.clearRequest(context, request.id)
        scope.launch {
            listen.respond(request.id, accept)
            requests.refresh()
        }
    }

    NeoAccentCard(fill = c.sky, radius = 16.dp) {
        Text(
            "${request.handle} wants to listen along",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (request.title.isNotBlank()) {
            Text(
                request.title,
                style = MaterialTheme.typography.bodySmall,
                color = c.onAccent.copy(alpha = 0.75f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            NeoButton("Let them in", small = true, tone = NeoTone.Lime, enabled = !busy, onClick = {
                answer(true)
            })
            NeoButton("No", small = true, tone = NeoTone.Paper, enabled = !busy, onClick = {
                answer(false)
            })
        }
    }
}

@Composable
private fun FriendRequestCard(request: PendingRequest) {
    val context = LocalContext.current
    val c = Neo.colors
    val scope = rememberCoroutineScope()
    val friends = remember { FriendsRepository.get(context) }
    val requests = remember { RequestsRepository.get(context) }
    var busy by remember(request.profile.id) { mutableStateOf(false) }

    fun answer(accept: Boolean) {
        busy = true
        scope.launch {
            if (accept) friends.accept(request.profile) else friends.remove(request.profile)
            requests.refresh()
        }
    }

    NeoAccentCard(fill = c.lime, radius = 16.dp) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Face(request.profile.handle, request.profile.avatarUrl, 38.dp)
            Text(
                request.profile.handle,
                style = MaterialTheme.typography.titleMedium,
                color = c.onAccent,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            NeoButton("Accept", small = true, tone = NeoTone.Violet, enabled = !busy, onClick = {
                answer(true)
            })
            NeoButton("Decline", small = true, tone = NeoTone.Paper, enabled = !busy, onClick = {
                answer(false)
            })
        }
    }
}

/**
 * One you sent, still unanswered.
 *
 * Nothing to do about it except take it back, which is worth offering: a
 * request sent to the wrong handle otherwise sits there for ever with no way
 * to tidy it up.
 */
@Composable
private fun SentRequestCard(request: PendingRequest) {
    val context = LocalContext.current
    val c = Neo.colors
    val scope = rememberCoroutineScope()
    val friends = remember { FriendsRepository.get(context) }
    val requests = remember { RequestsRepository.get(context) }
    var busy by remember(request.profile.id) { mutableStateOf(false) }

    NeoCard(radius = 14.dp, shadow = 3.dp, padding = 12.dp) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Face(request.profile.handle, request.profile.avatarUrl, 34.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    request.profile.handle,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Note("Waiting for them")
            }
            NeoButton("Withdraw", small = true, tone = NeoTone.Paper, enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    friends.remove(request.profile)
                    requests.refresh()
                }
            })
        }
    }
}
