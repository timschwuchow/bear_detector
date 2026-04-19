package com.beardetector.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beardetector.network.AlertReceiver
import com.beardetector.network.Discovery
import com.beardetector.notification.NotificationHelper
import com.beardetector.ui.theme.AlertRed
import com.beardetector.ui.theme.SafeGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MonitorScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var alertActive by remember { mutableStateOf(false) }
    var alertCount by remember { mutableIntStateOf(0) }
    var listenerCount by remember { mutableIntStateOf(0) }

    // Start discovery and alert receiver
    DisposableEffect(Unit) {
        val discovery = Discovery()
        val receiver = AlertReceiver()

        discovery.startBroadcasting("MONITOR")
        discovery.startListening { mode, _ ->
            if (mode == "LISTEN") {
                listenerCount = discovery.getPeerCount("LISTEN")
            }
        }

        receiver.startListening {
            alertActive = true
            alertCount++
            NotificationHelper.showAlert(context)
            scope.launch {
                delay(5000)
                alertActive = false
            }
        }

        onDispose {
            discovery.stop()
            receiver.stop()
        }
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
            TextButton(onClick = onBack) {
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

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Listening for alerts on this network...",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}
