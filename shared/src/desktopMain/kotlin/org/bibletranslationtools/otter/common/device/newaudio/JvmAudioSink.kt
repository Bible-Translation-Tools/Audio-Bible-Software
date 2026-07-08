package org.bibletranslationtools.otter.common.device.newaudio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine

class JvmAudioSink(
    private val lineProvider: () -> SourceDataLine?
) : AudioSink {

    private var currentLine: SourceDataLine? = null

    // Frames written into the line since the last flush (AudioBufferPlayer's contract:
    // framePosition restarts at 0 after a flush, like Android's AudioTrack).
    @Volatile
    private var writtenFrames: Long = 0
    @Volatile
    private var frameSizeBytes: Int = 2

    override val isRunning: Boolean
        get() = currentLine?.isRunning ?: false

    /**
     * The AUDIBLE position: frames written minus frames still queued in the line's
     * buffer. SourceDataLine.longFramePosition is NOT usable here — on macOS it tracks
     * frames consumed into the native buffer (write-side), which runs ahead of the
     * speaker by the whole buffer depth (~400 ms measured) and never resets on flush.
     * `bufferSize - available()` is the queued byte count, which JavaSound keeps
     * current as the mixer drains, giving a played estimate accurate to the mixer
     * callback interval.
     */
    override val framePosition: Long
        get() {
            val line = currentLine ?: return 0
            val queuedFrames = (line.bufferSize - line.available()) / frameSizeBytes
            return (writtenFrames - queuedFrames).coerceAtLeast(0L)
        }

    override fun open(spec: AudioSpec) {
        val line = lineProvider() ?: throw IllegalStateException("No SourceDataLine available")

        val format = AudioFormat(
            spec.sampleRate.toFloat(),
            spec.bitDepth,
            spec.channels,
            true, // signed
            spec.isBigEndian
        )

        if (line.isOpen) {
            line.stop()
            line.flush()
            line.close()
        }
        line.open(format)
        currentLine = line
        frameSizeBytes = ((spec.bitDepth / 8) * spec.channels).coerceAtLeast(1)
        writtenFrames = 0
    }

    override fun start() {
        currentLine?.start()
    }

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        val written = currentLine?.write(data, offset, size) ?: 0
        writtenFrames += written / frameSizeBytes
        return written
    }

    override fun stop() {
        currentLine?.stop()
    }

    override fun drain() {
        currentLine?.drain()
    }

    override fun flush() {
        currentLine?.flush()
        // Queued audio is discarded; position accounting restarts (the player
        // re-anchors sessionStartFrame on the next play/seek).
        writtenFrames = 0
    }

    override fun close() {
        currentLine?.close()
        currentLine = null
    }
}
