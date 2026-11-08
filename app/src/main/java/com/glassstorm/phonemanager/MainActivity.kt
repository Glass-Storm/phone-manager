package com.glassstorm.phonemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.glassstorm.phonemanager.ui.HubTheme
import com.glassstorm.phonemanager.ui.TranslationApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // HubTheme wraps the generated kit and re-points its palette (DESIGN.md).
            HubTheme {
                TranslationApp()
            }
        }
    }
}
