package org.bibletranslationtools.otter.common.device

/**
 * A reader whose audio identifies itself: each buffer carries, in its first four bytes, the take frame
 * its first sample belongs to.
 *
 * That is what lets [BufferedAudioSink] report which frame of the take is actually audible instead of
 * merely how many frames have gone past. Without it, "the audio is ahead of the waveform" cannot be
 * measured — only guessed at from frame counts, which say nothing about *which* content was heard, and
 * so cannot detect a pause/resume that skips or replays a stretch of the take.
 */
class IndexedAudioFileReader(
    override val totalFrames: Int = 44_100,
    override val spec: AudioSpec = AudioSpec(sampleRate = 44_100, bitDepth = 16, channels = 1)
) : AudioFileReader {

    override var framePosition: Int = 0
    private var isOpen = false

    override fun hasRemaining(): Boolean = framePosition < totalFrames

    override fun getPcmBuffer(bytes: ByteArray): Int {
        if (!isOpen || !hasRemaining()) return 0

        val framesRequested = bytes.size / spec.bytesPerFrame
        val framesToRead = minOf(framesRequested, totalFrames - framePosition)
        val bytesToReturn = framesToRead * spec.bytesPerFrame

        for (i in 0 until bytesToReturn) bytes[i] = 0
        encodeContentStart(bytes, 0, framePosition.toLong())

        framePosition += framesToRead
        return bytesToReturn
    }

    override fun seek(frame: Long) {
        framePosition = frame.toInt().coerceIn(0, totalFrames)
    }

    override fun open() {
        isOpen = true
    }

    override fun release() {
        isOpen = false
    }

    companion object {
        /** Little-endian, split across two 16-bit samples so the stamp survives as ordinary PCM. */
        fun encodeContentStart(bytes: ByteArray, offset: Int, contentStart: Long) {
            if (bytes.size - offset < HEADER_BYTES) return
            val value = contentStart.toInt()
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        fun decodeContentStart(bytes: ByteArray, offset: Int): Long {
            if (bytes.size - offset < HEADER_BYTES) return 0L
            val value = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
            return value.toLong()
        }

        private const val HEADER_BYTES = 4
    }
}
