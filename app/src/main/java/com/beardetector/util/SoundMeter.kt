package com.beardetector.util

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class SoundMeter {

    companion object {
        /** Samples per chunk (~23 ms at 44100 Hz). One UDP datagram per chunk. */
        const val CHUNK_SAMPLES = 1024

        /** RMS amplitude of [buffer] over the first [length] samples. */
        fun rms(buffer: ShortArray, length: Int = buffer.size): Double {
            if (length <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until length) {
                sum += buffer[i].toDouble() * buffer[i].toDouble()
            }
            return sqrt(sum / length)
        }
    }

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
     * Blocking read of one [CHUNK_SAMPLES]-sample chunk from the mic. Must run on a
     * background thread. Returns:
     *  - a full [CHUNK_SAMPLES] buffer on a normal read,
     *  - a buffer trimmed to the valid samples on a positive partial read,
     *  - null when the recorder is gone, returns an error code (negative), or yields no
     *    data. A null return means "don't spin" — the caller should back off, because an
     *    error code (e.g. ERROR_DEAD_OBJECT) returns immediately rather than blocking.
     */
    fun readChunk(): ShortArray? {
        val buffer = ShortArray(CHUNK_SAMPLES)
        val read = recorder?.read(buffer, 0, CHUNK_SAMPLES) ?: return null
        return when {
            read == CHUNK_SAMPLES -> buffer
            read > 0 -> buffer.copyOf(read) // partial read — keep the valid samples
            else -> null                    // 0 (no data) or negative error code
        }
    }
}
