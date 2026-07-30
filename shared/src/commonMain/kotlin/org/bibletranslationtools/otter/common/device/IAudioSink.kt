package org.bibletranslationtools.otter.common.device

interface AudioSink {
    /**
     * Opens the audio line with the specified format.
     * Should throw an exception if the format is unsupported.
     */
    fun open(spec: AudioSpec)

    /**
     * Starts playback.
     */
    fun start()

    /**
     * Pushes PCM data to the hardware buffer.
     * Returns the number of bytes actually written.
     */
    fun write(data: ByteArray, offset: Int, size: Int): Int

    /**
     * Stops playback immediately.
     */
    fun stop()

    /**
     * Blocks until all data in the buffer has been played.
     */
    fun drain()

    /**
     * Clears any queued data in the hardware buffer.
     */
    fun flush()

    /**
     * Closes the line and releases hardware resources.
     */
    fun close()

    val isRunning: Boolean
    val framePosition: Long
}