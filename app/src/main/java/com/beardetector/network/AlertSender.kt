package com.beardetector.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object AlertSender {

    private const val TAG = "AlertSender"
    private const val ALERT_PORT = 9878

    suspend fun sendAlert(targetIps: List<String>) = withContext(Dispatchers.IO) {
        val message = "BEAR_ALERT:${System.currentTimeMillis()}"
        val data = message.toByteArray()

        for (ip in targetIps) {
            try {
                DatagramSocket().use { socket ->
                    val packet = DatagramPacket(
                        data, data.size,
                        InetAddress.getByName(ip), ALERT_PORT
                    )
                    socket.send(packet)
                    Log.d(TAG, "Alert sent to $ip")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send alert to $ip: ${e.message}")
            }
        }
    }
}
