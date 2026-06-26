package com.beardetector.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beardetector.service.MonitorService
import com.beardetector.ui.theme.AlertRed
import com.beardetector.ui.theme.SafeGreen

@Composable
fun MonitorScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(MonitorService.isRunning.value) }
    val alertActive by MonitorService.alertActive.collectAsState()
    val alertCount by MonitorService.alertCount.collectAsState()
    val listenerCount by MonitorService.listenerCount.collectAsState()
    val isListening by MonitorService.isListening.collectAsState()

    fun startMonitoring() {
        // Prompt to disable battery optimization if needed (mirrors ListenScreen).
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            val batteryIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(batteryIntent)
        }
        context.startForegroundService(Intent(context, MonitorService::class.java))
        isRunning = true
    }

    fun stopMonitoring() {
        context.stopService(Intent(context, MonitorService::class.java))
        isRunning = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                if (isRunning) stopMonitoring()
                onBack()
            }) {
                Text("Back")
            }
            Text(
                text = "Monitor Mode",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Spacer(modifier = Modifier.height(64.dp))

        // Big status indicator
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(if (alertActive) AlertRed else SafeGreen),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (alertActive) "BEAR\nDETECTED!" else "All\nQuiet",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Connected listeners: $listenerCount",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Alerts received: $alertCount",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Listen / Mute toggle — drives AudioPlayer playback on the service.
        if (isRunning) {
            Button(
                onClick = { MonitorService.setListening(!isListening) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = if (isListening) "Mute" else "Listen",
                    fontSize = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Start/Stop monitoring button
        Button(
            onClick = { if (isRunning) stopMonitoring() else startMonitoring() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) AlertRed else MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(
                text = if (isRunning) "Stop Monitoring" else "Start Monitoring",
                fontSize = 18.sp
            )
        }
    }
}
