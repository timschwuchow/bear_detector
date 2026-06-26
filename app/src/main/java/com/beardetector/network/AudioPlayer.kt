package com.beardetector.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Receives raw PCM chunks over UDP (port 9879) and plays them through [AudioTrack].
 *
 * Audio params match the sender exactly: 44100 Hz, mono, PCM 16-bit, little-endian.
 * Packets are written straight to [AudioTrack] (no short reassembly). Muted by default —
 * received packets are discarded until [setPlaying] is called with true.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val AUDIO_PORT = 9879
        private const val SAMPLE_RATE = 44100
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var receiveJob: Job? = null
    private var socket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var playing = false

    @Volatile
    var lastPacketTime = 0L
        private set

    fun start() {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            // *2 for a few chunks of jitter headroom against bursty packet arrival.
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track

        receiveJob = scope.launch {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(AUDIO_PORT))
                }
                socket = s
                val buffer = ByteArray(4096)
                Log.d(TAG, "Receiving audio on port $AUDIO_PORT")

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        s.receive(packet)
                        lastPacketTime = System.currentTimeMillis()
                        // AudioTrack.write() is blocking — fine here on Dispatchers.IO.
                        if (playing) {
                            track.write(packet.data, 0, packet.length)
                        }
                        // When muted, discard the packet and keep the track paused.
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "Audio receive failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio listener failed: ${e.message}")
            }
        }
    }

    /** true → unmute and play; false → mute (pause + flush buffered audio). */
    fun setPlaying(on: Boolean) {
        playing = on
        val track = audioTrack ?: return
        try {
            if (on) {
                track.play()
            } else {
                track.pause()
                track.flush()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setPlaying($on) failed: ${e.message}")
        }
    }

    fun stop() {
        receiveJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        playing = false
    }
}
