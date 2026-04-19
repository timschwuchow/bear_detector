package com.beardetector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.beardetector.notification.NotificationHelper
import com.beardetector.ui.BearDetectorApp
import com.beardetector.ui.theme.BearDetectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.createChannel(this)
        setContent {
            BearDetectorTheme {
                BearDetectorApp()
            }
        }
    }
}
