package com.glassstorm.phonemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.glassstorm.phonemanager.ui.AppShell
import com.glassstorm.phonemanager.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppShell()
            }
        }
    }
}
