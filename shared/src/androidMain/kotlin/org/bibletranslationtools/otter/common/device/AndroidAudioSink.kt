package org.bibletranslationtools.otter.common.device

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class AndroidAudioSink : AudioSink {
    private var audioTrack: AudioTrack? = null

    override val isRunning: Boolean
        get() = audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING

    override val framePosition: Long
        get() = audioTrack?.playbackHeadPosition?.toLong() ?: 0L

    fun getAudioTrack(): AudioTrack? = audioTrack

    override fun open(spec: AudioSpec) {
        // 1. Map AudioSpec to Android constants
        val channelConfig = if (spec.channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(spec.sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        // 2. Determine the internal hardware buffer size
        val minBufferSize = AudioTrack.getMinBufferSize(
            spec.sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // 3. Create the track in STREAM mode (for continuous PCM playback)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioTrack")
        }
    }

    override fun start() {
        audioTrack?.play()
    }

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        // WRITE_BLOCKING ensures this behaves similarly to the JVM logic
        return audioTrack?.write(data, offset, size, AudioTrack.WRITE_BLOCKING) ?: 0
    }

    override fun stop() {
        // pause(), not stop(): AudioTrack.stop() plays the remaining queue out before halting, which is
        // drain-then-stop, and AudioSink.stop() is specified to halt immediately. pause() halts at once
        // and KEEPS what is queued, which is the other half of the contract.
        //
        // This used to flush in between. That is the one thing stop() must not do: AudioBufferPlayer
        // pauses by stopping and resuming by starting, counting on the queue surviving, so flushing here
        // discarded up to a bufferful of audio on every pause — content the reader had already moved past,
        // so nothing downstream could tell it had gone missing.
        audioTrack?.pause()
    }

    override fun drain() {
        // In Android's streaming mode, stop() waits for the buffer to empty
        audioTrack?.stop()
    }

    override fun flush() {
        audioTrack?.flush()
    }

    override fun close() {
        audioTrack?.release()
        audioTrack = null
    }
}