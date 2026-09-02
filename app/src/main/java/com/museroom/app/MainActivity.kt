package com.museroom.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.museroom.app.ui.MuseroomTheme
import com.museroom.app.ui.NowPlayingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MuseroomTheme {
                NowPlayingScreen()
            }
        }
    }
}
