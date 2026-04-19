package com.beardetector.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.beardetector.R
import com.beardetector.network.AlertSender
import com.beardetector.network.Discovery
import com.beardetector.notification.NotificationHelper
import com.beardetector.util.SoundMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ListenService : Service() {

    companion object {
        private const val TAG = "ListenService"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_COOLDOWN_MS = 5000L

        val isRunning = MutableStateFlow(false)
        val currentAmplitude = MutableStateFlow(0.0)
        val alertActive = MutableStateFlow(false)
        val threshold = MutableStateFlow(2000f)
        val peerCount = MutableStateFlow(0)
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var listenJob: Job? = null
    private val soundMeter = SoundMeter()
    private val discovery = Discovery()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAlertTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        acquireWakeLock()
        acquireMulticastLock()
        startDiscovery()
        startListening()
        isRunning.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        listenJob?.cancel()
        soundMeter.stop()
        discovery.stop()
        releaseMulticastLock()
        releaseWakeLock()
        isRunning.value = false
        currentAmplitude.value = 0.0
        alertActive.value = false
        peerCount.value = 0
    }

    private fun startForegroundNotification() {
        val notification: Notification = NotificationCompat.Builder(this, NotificationHelper.LISTEN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listen)
            .setContentTitle("Bear Detector")
            .setContentText("Listening for sounds...")
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BearDetector::ListenWakeLock").apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("bear_detector_lock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
    }

    private fun startDiscovery() {
        discovery.startBroadcasting("LISTEN")
        discovery.startListening { mode, _ ->
            if (mode == "MONITOR") {
                peerCount.value = discovery.getPeerCount("MONITOR")
            }
        }
    }

    private fun startListening() {
        if (!soundMeter.start()) {
            Log.e(TAG, "Failed to start SoundMeter")
            stopSelf()
            return
        }

        listenJob = scope.launch {
            while (isActive) {
                val amplitude = soundMeter.getAmplitude()
                currentAmplitude.value = amplitude

                if (amplitude > threshold.value) {
                    val now = System.currentTimeMillis()
                    if (now - lastAlertTime > ALERT_COOLDOWN_MS) {
                        lastAlertTime = now
                        alertActive.value = true
                        Log.d(TAG, "Sound detected! Amplitude: $amplitude")

                        val monitors = discovery.getPeers("MONITOR")
                        if (monitors.isNotEmpty()) {
                            AlertSender.sendAlert(monitors)
                        }

                        launch {
                            delay(3000)
                            alertActive.value = false
                        }
                    }
                }

                delay(200) // Check every 200ms
            }
        }
    }
}
