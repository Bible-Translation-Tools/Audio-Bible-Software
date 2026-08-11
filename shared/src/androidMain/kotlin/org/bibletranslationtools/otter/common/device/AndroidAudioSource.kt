package org.bibletranslationtools.otter.common.device

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AndroidAudioSource : AudioSource {
    private var audioRecord: AudioRecord? = null

    fun getAudioRecord(): AudioRecord? = audioRecord

    @SuppressLint("MissingPermission") // Assumes permission is handled at the UI/App level
    override fun open(spec: AudioSpec) {
        val channelConfig = if (spec.channels == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        // 1. Calculate the minimum buffer size required for this hardware/format combo
        val minBufferSize = AudioRecord.getMinBufferSize(
            spec.sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalArgumentException("Unsupported AudioSpec for Android AudioRecord")
        }

        // 2. Initialize the AudioRecord instance
        // We use MediaRecorder.AudioSource.MIC as the default source
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            spec.sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize.coerceAtLeast(2048) // Ensure a healthy buffer size
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord")
        }
    }

    override fun start() {
        audioRecord?.startRecording()
    }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        // READ_BLOCKING ensures consistency with the JVM read behavior
        return audioRecord?.read(buffer, offset, size, AudioRecord.READ_BLOCKING) ?: 0
    }

    override fun stop() {
        // On Android, stopping the record object stops the capture but
        // doesn't release the hardware immediately.
        audioRecord?.stop()
    }

    override fun close() {
        audioRecord?.release()
        audioRecord = null
    }
}