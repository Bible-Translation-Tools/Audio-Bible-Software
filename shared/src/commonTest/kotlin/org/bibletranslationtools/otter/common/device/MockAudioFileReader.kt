package org.bibletranslationtools.otter.common.device

class MockAudioFileReader(
    override val totalFrames: Int = 44100,
    override val spec: AudioSpec = AudioSpec(sampleRate = 44100, bitDepth = 16, channels = 1)
) : AudioFileReader {

    override var framePosition: Int = 0
    private var isOpen = false

    override fun hasRemaining(): Boolean = framePosition < totalFrames

    override fun getPcmBuffer(bytes: ByteArray): Int {
        if (!isOpen || !hasRemaining()) return 0

        // Calculate how many frames we can fit in this byte array
        val framesRequested = bytes.size / spec.bytesPerFrame
        val framesRemaining = totalFrames - framePosition
        val framesToRead = minOf(framesRequested, framesRemaining)

        val bytesToReturn = framesToRead * spec.bytesPerFrame

        // Fill with zeros (silence)
        for (i in 0 until bytesToReturn) { bytes[i] = 0 }

        framePosition += framesToRead
        return bytesToReturn
    }

    override fun seek(frame: Long) {
        framePosition = frame.toInt().coerceIn(0, totalFrames)
    }

    override fun open() { isOpen = true }
    override fun release() { isOpen = false }
}