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
     * Stops playback immediately, **keeping** whatever is queued and whatever [framePosition] has
     * reached. Only [flush] discards either.
     *
     * This is what makes a pause exact rather than estimated: with the queue intact, resuming is a
     * [start] and the sound continues from the frame it stopped on — nothing to replay, nothing to skip,
     * no arithmetic to get wrong. A sink that discards here silently swallows up to a bufferful of
     * content on every pause, and [AudioBufferPlayer] cannot detect that it happened.
     */
    fun stop()

    /**
     * Blocks until all data in the buffer has been played.
     */
    fun drain()

    /**
     * Clears any queued data in the hardware buffer, and resets [framePosition] to 0.
     */
    fun flush()

    /**
     * Closes the line and releases hardware resources.
     */
    fun close()

    /**
     * Whether [framePosition] is the audible position. NOT "is the mixer presenting data this instant" —
     * a line whose buffer momentarily empties still knows exactly what has been heard. See
     * `JvmAudioSink.isRunning`, where conflating the two cost two separate position bugs.
     */
    val isRunning: Boolean

    /**
     * Frames played since the last [flush].
     *
     * Must never exceed the frames written since that flush: a sink may lag what it was handed (the queue
     * is exactly that lag) but may never claim to have played audio it was never given.
     * [AudioBufferPlayer] relies on this bound rather than re-deriving it, because the writer's own
     * position stops being an upper bound the moment a pause retains a queue.
     */
    val framePosition: Long
}