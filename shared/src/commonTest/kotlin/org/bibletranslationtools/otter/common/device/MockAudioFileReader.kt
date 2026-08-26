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

    /**
     * Throws when the reader is not open, exactly as the real ones do
     * (`WavFileReader.seek`: "Tried to seek before opening file").
     *
     * This used to seek happily whether open or not, and that leniency hid a live bug for an entire
     * debugging session: a connection was closing a reader the worker still held, every subsequent
     * `connect()` threw out of `pause()`, and playback died app-wide — while every test here passed,
     * because this double did not care. A double that is kinder than the real thing cannot prove
     * anything about the real thing.
     */
    override fun seek(frame: Long) {
        check(isOpen) { "Tried to seek before opening file" }
        framePosition = frame.toInt().coerceIn(0, totalFrames)
    }

    override fun open() { isOpen = true }
    override fun release() { isOpen = false }
}