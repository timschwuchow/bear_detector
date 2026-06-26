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
import com.beardetector.network.AudioStreamer
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
    private val audioStreamer = AudioStreamer()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAlertTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Mark that listening was running so a reboot can prompt the user to resume.
        // Cleared only on explicit user Stop (ListenScreen), so an OS-kill + reboot still prompts.
        BootReceiver.setListenWasRunning(this, true)
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
        audioStreamer.close()
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
                // Blocking read paces this loop (~23 ms/chunk) — no delay needed.
                val chunk = soundMeter.readChunk() ?: continue
                val amplitude = SoundMeter.rms(chunk)
                currentAmplitude.value = amplitude

                val monitors = discovery.getPeers("MONITOR")

                if (amplitude > threshold.value) {
                    val now = System.currentTimeMillis()
                    if (now - lastAlertTime > ALERT_COOLDOWN_MS) {
                        lastAlertTime = now
                        alertActive.value = true
                        Log.d(TAG, "Sound detected! Amplitude: $amplitude")

                        if (monitors.isNotEmpty()) {
                            AlertSender.sendAlert(monitors)
                        }

                        launch {
                            delay(3000)
                            alertActive.value = false
                        }
                    }
                }

                // Always-on streaming: send every chunk to every discovered monitor.
                audioStreamer.send(chunk, chunk.size, monitors)
            }
        }
    }
}
