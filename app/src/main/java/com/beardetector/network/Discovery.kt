package com.beardetector.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

class Discovery {

    companion object {
        private const val TAG = "Discovery"
        private const val PORT = 9877
        private const val BROADCAST_INTERVAL_MS = 3000L
        private const val PEER_EXPIRY_MS = 15000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var broadcastSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null

    // ip -> (mode, lastSeen)
    private val peers = mutableMapOf<String, Pair<String, Long>>()

    fun startBroadcasting(mode: String) {
        broadcastJob = scope.launch {
            try {
                val socket = DatagramSocket().apply { broadcast = true }
                broadcastSocket = socket
                val localIp = getLocalIpAddress()
                Log.d(TAG, "Broadcasting as $mode from $localIp")

                while (isActive) {
                    try {
                        val message = "BEAR_$mode:$localIp"
                        val data = message.toByteArray()
                        val packet = DatagramPacket(
                            data, data.size,
                            getBroadcastAddress(), PORT
                        )
                        socket.send(packet)
                    } catch (e: Exception) {
                        Log.w(TAG, "Broadcast send failed: ${e.message}")
                    }
                    delay(BROADCAST_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast failed: ${e.message}")
            }
        }
    }

    fun startListening(onPeerFound: (mode: String, ip: String) -> Unit) {
        listenJob = scope.launch {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(PORT))
                }
                listenSocket = socket
                val buffer = ByteArray(256)
                Log.d(TAG, "Listening for peers on port $PORT")

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length)

                        // Parse: BEAR_MODE:IP
                        if (message.startsWith("BEAR_")) {
                            val parts = message.removePrefix("BEAR_").split(":", limit = 2)
                            if (parts.size == 2) {
                                val mode = parts[0]
                                val ip = parts[1]
                                val localIp = getLocalIpAddress()
                                if (ip != localIp) {
                                    synchronized(peers) {
                                        peers[ip] = Pair(mode, System.currentTimeMillis())
                                    }
                                    onPeerFound(mode, ip)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "Listen receive failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listen failed: ${e.message}")
            }
        }

        // Peer expiry cleanup
        scope.launch {
            while (isActive) {
                delay(5000)
                val now = System.currentTimeMillis()
                synchronized(peers) {
                    peers.entries.removeAll { now - it.value.second > PEER_EXPIRY_MS }
                }
            }
        }
    }

    fun getPeers(mode: String): List<String> {
        synchronized(peers) {
            return peers.filter { it.value.first == mode }.keys.toList()
        }
    }

    fun getPeerCount(mode: String): Int {
        synchronized(peers) {
            return peers.count { it.value.first == mode }
        }
    }

    fun stop() {
        broadcastJob?.cancel()
        listenJob?.cancel()
        try { broadcastSocket?.close() } catch (_: Exception) {}
        try { listenSocket?.close() } catch (_: Exception) {}
    }

    private fun getLocalIpAddress(): String {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }

    private fun getBroadcastAddress(): InetAddress {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (intf.isLoopback || !intf.isUp) continue
                for (ifAddr in intf.interfaceAddresses) {
                    val broadcast = ifAddr.broadcast
                    if (broadcast != null) return broadcast
                }
            }
        } catch (_: Exception) {}
        return InetAddress.getByName("255.255.255.255")
    }
}
