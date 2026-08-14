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

        // 2. Determine the internal hardware buffer size.
        //
        // getMinBufferSize is a FLOOR, not a recommendation: it is the smallest buffer that can play
        // without glitching when the writer always refills in time. We are a Java-side writer feeding a
        // pure-Java MP3 decoder from a coroutine dispatcher, competing with a Compose render thread on
        // whatever core the governor gives us — so "always in time" is not a property we have. On an
        // Android 7 tablet that showed up as `AudioTrack: releaseBuffer() track disabled due to previous
        // underrun` followed by `AudioFlinger: BUFFER TIMEOUT: remove(...) from active list`: one late
        // refill and the platform drops the track from the mixer, so source playback ran a few seconds
        // and then stopped dead.
        //
        // The headroom is what absorbs that jitter, and it is close to free here. The playhead is drawn
        // from the hardware play cursor (AudioBufferPlayer.playCursor), not from how far the writer has
        // got, so a deeper queue does not desync the waveform; pause() and seek() both flush, so it does
        // not lengthen either. What it does cost is memory, hence a multiplier rather than a fixed slab.
        val minBufferSize = AudioTrack.getMinBufferSize(
            spec.sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufferSize * BUFFER_HEADROOM_FACTOR

        // 3. Create the track in STREAM mode (for continuous PCM playback)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
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

    private companion object {
        /**
         * How many times the platform minimum to queue ahead. 4x at 44.1kHz mono/16-bit is on the order
         * of a few hundred ms — enough to ride out a GC pause or a busy render frame, small enough that
         * the flush on pause/seek stays imperceptible.
         */
        const val BUFFER_HEADROOM_FACTOR = 4
    }
}