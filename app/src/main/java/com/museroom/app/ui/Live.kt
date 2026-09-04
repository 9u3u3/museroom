package com.museroom.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * Keeping what is on screen true while somebody is looking at it.
 *
 * Screens used to fetch once, when they were first drawn, and then sit there.
 * Leaving a tab and coming back was the only thing that reliably updated
 * anything, and that only worked by accident: switching tabs throws the screen
 * away, so coming back builds a new one that fetches again. Standing still on a
 * screen watching a number that had stopped being true was the normal case.
 *
 * Two things bring a screen back to life, and the second is the one that was
 * missing everywhere. The first is time. The second is the person returning to
 * the app at all, which is the moment they are most likely to be looking and
 * the moment the data is most likely to be old.
 *
 * A period of zero means the keys and coming back are the only reasons to look
 * again, which is right for anything expensive that does not drift on its own.
 */
@Composable
fun Refreshing(
    vararg keys: Any?,
    everyMs: Long = 20_000L,
    block: suspend () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    var resumes by remember { mutableIntStateOf(0) }

    DisposableEffect(owner) {
        // Handing an observer to a lifecycle that is already resumed replays
        // the event at once. That first one is the composition we are standing
        // in, which is about to fetch anyway, so counting it would mean every
        // screen fetching twice on the way in.
        var replayed = false
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (replayed) resumes++ else replayed = true
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(*keys, resumes, everyMs) {
        while (true) {
            block()
            if (everyMs <= 0L) break
            delay(everyMs)
        }
    }
}
