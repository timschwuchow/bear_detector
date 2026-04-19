package com.beardetector.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class AlertReceiver {

    companion object {
        private const val TAG = "AlertReceiver"
        private const val ALERT_PORT = 9878
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var listenJob: Job? = null
    private var socket: DatagramSocket? = null

    fun startListening(onAlert: () -> Unit) {
        listenJob = scope.launch {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(ALERT_PORT))
                }
                socket = s
                val buffer = ByteArray(256)
                Log.d(TAG, "Listening for alerts on port $ALERT_PORT")

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        s.receive(packet)
                        val message = String(packet.data, 0, packet.length)

                        if (message.startsWith("BEAR_ALERT:")) {
                            Log.d(TAG, "Alert received: $message")
                            onAlert()
                        }
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "Alert receive failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Alert listener failed: ${e.message}")
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
    }
}
