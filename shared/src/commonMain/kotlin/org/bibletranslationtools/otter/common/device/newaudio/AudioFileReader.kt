package org.bibletranslationtools.otter.common.device.newaudio

import java.io.Closeable

interface AudioFileReader : Closeable, AutoCloseable {
    // New: Unified specification
    val spec: AudioSpec

    // Kept for convenience/compatibility
    val framePosition: Int
    val totalFrames: Int

    fun hasRemaining(): Boolean

    /**
     * Reads from the underlying audio file and writes decoded PCM data to [bytes].
     * @return the number of bytes written.
     */
    fun getPcmBuffer(bytes: ByteArray): Int

    fun seek(frame: Long) // Changed to Long for consistency with Sink
    fun open()
    fun release()

    fun supportsTimeShifting(): Boolean = true

    override fun close() = release()
}