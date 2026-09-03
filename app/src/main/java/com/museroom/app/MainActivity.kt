package com.museroom.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.museroom.app.ui.MuseroomApp
import com.museroom.app.ui.MuseroomTheme
import com.museroom.app.ui.ThemeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val theme = remember { ThemeState.get(context) }
            val dark by theme.dark.collectAsState()
            MuseroomTheme(dark = dark) {
                MuseroomApp()
            }
        }
    }
}
