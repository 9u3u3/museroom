package com.museroom.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.museroom.app.player.Playback
import com.museroom.app.sync.RoomPlayer
import com.museroom.app.ui.MuseroomApp
import com.museroom.app.ui.MuseroomTheme
import com.museroom.app.ui.ThemeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Before anything is composed, not from an effect inside it.
        // Library's flows are fields that attach() replaces, so a screen that
        // read one during the first composition would hold the empty
        // placeholder for ever and Home would come up with no shelves on it.
        Playback.attach(this)

        setContent {
            val context = LocalContext.current
            val theme = remember { ThemeState.get(context) }
            val dark by theme.dark.collectAsState()
            MuseroomTheme(dark = dark) {
                MuseroomApp()
            }
        }

        // Museroom plays a listening room itself, from a WebView it keeps one
        // pixel wide. It needs a window to live in, and this is the window;
        // the player outlives this activity so that a rotation is not a reason
        // for the music to stop.
        RoomPlayer.prime(this)
        RoomPlayer.attach(this)
    }

    override fun onDestroy() {
        RoomPlayer.detach()
        super.onDestroy()
    }
}
