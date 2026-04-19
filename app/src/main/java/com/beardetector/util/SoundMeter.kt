package com.beardetector.util

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class SoundMeter {

    private var recorder: AudioRecord? = null
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    fun start(): Boolean {
        return try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                recorder?.release()
                recorder = null
                return false
            }
            recorder?.startRecording()
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        try {
            recorder?.stop()
        } catch (_: IllegalStateException) {
        }
        recorder?.release()
        recorder = null
    }

    /**
     * Reads a buffer from the mic and returns the RMS amplitude.
     * Must be called from a background thread (blocking read).
     */
    fun getAmplitude(): Double {
        val buffer = ShortArray(bufferSize / 2)
        val read = recorder?.read(buffer, 0, buffer.size) ?: return 0.0
        if (read <= 0) return 0.0

        var sum = 0.0
        for (i in 0 until read) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        return sqrt(sum / read)
    }
}
