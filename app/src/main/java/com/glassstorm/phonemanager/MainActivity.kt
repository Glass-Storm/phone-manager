package com.glassstorm.phonemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.glassstorm.phonemanager.ui.AppShell
import com.glassstorm.phonemanager.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The process-wide Dagger graph the Application built; the ViewModel factory
        // it exposes is what lets each Compose screen resolve its ports by type.
        val viewModelFactory = (application as PhoneManagerApplication).component.viewModelFactory()
        setContent {
            AppTheme {
                AppShell(viewModelFactory = viewModelFactory)
            }
        }
    }
}
