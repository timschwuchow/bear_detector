package com.beardetector.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beardetector.service.ListenService
import com.beardetector.ui.theme.AlertRed
import com.beardetector.ui.theme.SafeGreen

@Composable
fun ListenScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(ListenService.isRunning.value) }
    var threshold by remember { mutableFloatStateOf(ListenService.threshold.value) }
    val amplitude by ListenService.currentAmplitude.collectAsState()
    val alertActive by ListenService.alertActive.collectAsState()
    val peerCount by ListenService.peerCount.collectAsState()

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
                if (isRunning) {
                    context.stopService(Intent(context, ListenService::class.java))
                    isRunning = false
                }
                onBack()
            }) {
                Text("Back")
            }
            Text(
                text = "Listen Mode",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Status indicator
        Text(
            text = if (!isRunning) "Idle" else if (alertActive) "SOUND DETECTED!" else "Listening...",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                !isRunning -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                alertActive -> AlertRed
                else -> SafeGreen
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Amplitude display
        Text(
            text = "Amplitude: ${amplitude.toInt()}",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Text(
            text = "Threshold: ${threshold.toInt()}",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Sensitivity slider
        Text("Sensitivity", fontSize = 14.sp)
        Slider(
            value = threshold,
            onValueChange = {
                threshold = it
                ListenService.threshold.value = it
            },
            valueRange = 500f..10000f,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("More sensitive", fontSize = 12.sp)
            Text("Less sensitive", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Connected monitors: $peerCount",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.weight(1f))

        // Start/Stop button
        Button(
            onClick = {
                if (isRunning) {
                    context.stopService(Intent(context, ListenService::class.java))
                    isRunning = false
                } else {
                    // Prompt to disable battery optimization if needed
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        val batteryIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(batteryIntent)
                    }
                    val intent = Intent(context, ListenService::class.java)
                    context.startForegroundService(intent)
                    isRunning = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) AlertRed else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isRunning) "Stop Listening" else "Start Listening",
                fontSize = 18.sp
            )
        }
    }
}
