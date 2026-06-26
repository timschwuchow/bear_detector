package com.beardetector.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.beardetector.MainActivity
import com.beardetector.R

object NotificationHelper {

    const val LISTEN_CHANNEL_ID = "bear_listen_channel"
    const val MONITOR_CHANNEL_ID = "bear_monitor_channel"
    const val ALERT_CHANNEL_ID = "bear_alert_channel"
    private const val ALERT_NOTIFICATION_ID = 2001
    private const val RESUME_NOTIFICATION_ID = 2002

    fun createChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Low-priority channel for the foreground service
        val listenChannel = NotificationChannel(
            LISTEN_CHANNEL_ID,
            "Listening Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing notification while listening for sounds"
        }
        manager.createNotificationChannel(listenChannel)

        // Low-priority channel for the monitor foreground service
        val monitorChannel = NotificationChannel(
            MONITOR_CHANNEL_ID,
            "Monitoring Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitoring baby's room"
        }
        manager.createNotificationChannel(monitorChannel)

        // High-priority channel for alerts
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Bear Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Sound detected alerts from baby's room"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
        }
        manager.createNotificationChannel(alertChannel)
    }

    fun showAlert(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle("Bear Detected!")
            .setContentText("Sound detected in baby's room")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Posts a high-priority prompt to reopen the app after a reboot. We can't silently
     * restart the mic service from a boot receiver (Android 14+ blocks background mic
     * access), so tapping this opens MainActivity where the user can start it with full
     * permission.
     */
    fun showResumePrompt(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listen)
            .setContentTitle("Tap to resume monitoring")
            .setContentText("Bear Detector stopped after a restart. Tap to start listening again.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(RESUME_NOTIFICATION_ID, notification)
    }
}
