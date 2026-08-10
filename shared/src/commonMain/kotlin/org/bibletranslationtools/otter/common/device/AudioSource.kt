package org.bibletranslationtools.otter.common.device

interface AudioSource {
    /**
     * Prepares the microphone for recording with the given spec.
     */
    fun open(spec: AudioSpec)

    fun start()

    /**
     * Reads raw PCM data from the hardware into the provided buffer.
     * Returns the number of bytes read.
     */
    fun read(data: ByteArray, offset: Int, size: Int): Int

    fun stop()

    fun close()
}