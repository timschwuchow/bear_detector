package com.beardetector.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.beardetector.R

object NotificationHelper {

    const val LISTEN_CHANNEL_ID = "bear_listen_channel"
    const val ALERT_CHANNEL_ID = "bear_alert_channel"
    private const val ALERT_NOTIFICATION_ID = 2001

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
}
