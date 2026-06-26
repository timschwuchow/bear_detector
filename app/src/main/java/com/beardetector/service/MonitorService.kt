package com.beardetector.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.beardetector.R
import com.beardetector.network.AlertReceiver
import com.beardetector.network.AudioPlayer
import com.beardetector.network.Discovery
import com.beardetector.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service for the parent's phone. Mirrors [ListenService]'s structure:
 * holds a wake lock + multicast lock so it survives overnight with the screen off.
 *
 * Runs Discovery (as MONITOR), the alert receiver, and the audio player. The audio
 * player receives the stream continuously but stays muted until the parent taps Listen
 * ([setListening]); alerts fire regardless of mute state.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val NOTIFICATION_ID = 1002
        private const val ALERT_RESET_MS = 5000L

        val isRunning = MutableStateFlow(false)
        val alertActive = MutableStateFlow(false)
        val alertCount = MutableStateFlow(0)
        val listenerCount = MutableStateFlow(0)
        val isListening = MutableStateFlow(false)

        // The screen sets this to toggle playback; the running service observes it.
        private var instance: MonitorService? = null

        /** Toggle audio playback (unmute/mute). No-op if the service isn't running. */
        fun setListening(on: Boolean) {
            instance?.audioPlayer?.setPlaying(on)
            isListening.value = on
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val discovery = Discovery()
    private val alertReceiver = AlertReceiver()
    private val audioPlayer = AudioPlayer()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startForegroundNotification()
        acquireWakeLock()
        acquireMulticastLock()
        startDiscovery()
        startAlertReceiver()
        audioPlayer.start()
        // Start muted; the parent taps Listen to hear audio.
        audioPlayer.setPlaying(false)
        isListening.value = false
        isRunning.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery.stop()
        alertReceiver.stop()
        audioPlayer.stop()
        releaseMulticastLock()
        releaseWakeLock()
        instance = null
        isRunning.value = false
        alertActive.value = false
        listenerCount.value = 0
        isListening.value = false
    }

    private fun startForegroundNotification() {
        val notification: Notification = NotificationCompat.Builder(this, NotificationHelper.MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listen)
            .setContentTitle("Bear Detector")
            .setContentText("Monitoring baby's room...")
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BearDetector::MonitorWakeLock").apply {
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
        multicastLock = wifi.createMulticastLock("bear_detector_monitor_lock").apply {
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
        discovery.startBroadcasting("MONITOR")
        discovery.startListening { mode, _ ->
            if (mode == "LISTEN") {
                listenerCount.value = discovery.getPeerCount("LISTEN")
            }
        }
    }

    private fun startAlertReceiver() {
        alertReceiver.startListening {
            alertActive.value = true
            alertCount.value = alertCount.value + 1
            NotificationHelper.showAlert(this)
            scope.launch {
                delay(ALERT_RESET_MS)
                alertActive.value = false
            }
        }
    }
}
