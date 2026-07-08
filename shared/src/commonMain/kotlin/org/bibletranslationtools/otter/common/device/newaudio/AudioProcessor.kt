package org.bibletranslationtools.otter.common.device.newaudio

interface AudioProcessor {
    /**
     * Initializes the processor for a specific format.
     * Must be called before [process].
     */
    fun configure(spec: AudioSpec)

    /**
     * Sets the playback speed (e.g., 1.0 for normal, 0.5 for half speed).
     */
    fun setPlaybackRate(rate: Double)

    /**
     * Takes a block of raw PCM bytes and returns the processed (stretched/shifted) bytes.
     * Note: The output size may differ from the input size during time-stretching.
     */
    fun process(input: ByteArray): ByteArray

    /**
     * The amount of overlap (in frames) required by the algorithm.
     * Useful for seeking and buffer calculations.
     */
    val overlap: Int

    /**
     * The ideal input buffer size (in bytes) for the current configuration.
     */
    val inputBufferSize: Int

    val playbackRate: Double
}