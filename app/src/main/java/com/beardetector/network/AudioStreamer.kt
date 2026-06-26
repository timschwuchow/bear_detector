package com.beardetector.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams raw PCM chunks to Monitor peers over UDP (port 9879).
 *
 * Unlike [AlertSender] (an object that opens a fresh socket per alert), this holds one
 * persistent [DatagramSocket] reused across all sends — at ~43 chunks/sec a per-call
 * socket would churn ephemeral ports. Instantiated and owned by ListenService.
 */
class AudioStreamer {

    companion object {
        private const val TAG = "AudioStreamer"
        private const val AUDIO_PORT = 9879
    }

    private var socket: DatagramSocket? = null
    // Cache InetAddress per IP string to avoid getByName() on every packet.
    private val addressCache = mutableMapOf<String, InetAddress>()

    private fun socket(): DatagramSocket {
        return socket ?: DatagramSocket().also { socket = it }
    }

    /**
     * Convert the first [length] shorts of [chunk] to little-endian bytes and send one
     * datagram to each IP in [targets]. Fire-and-forget — failures are logged, not thrown.
     */
    fun send(chunk: ShortArray, length: Int, targets: List<String>) {
        if (targets.isEmpty()) return

        val buffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) {
            buffer.putShort(chunk[i])
        }
        val data = buffer.array()

        for (ip in targets) {
            try {
                val address = addressCache.getOrPut(ip) { InetAddress.getByName(ip) }
                val packet = DatagramPacket(data, data.size, address, AUDIO_PORT)
                socket().send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stream to $ip: ${e.message}")
            }
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
