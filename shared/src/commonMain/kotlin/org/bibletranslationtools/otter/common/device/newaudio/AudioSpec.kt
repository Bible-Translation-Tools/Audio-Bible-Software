package org.bibletranslationtools.otter.common.device.newaudio

/**
 * Common representation of audio format parameters.
 */
data class AudioSpec(
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16, // 16 or 24
    val channels: Int = 1,  // 1 for Mono, 2 for Stereo
    val isBigEndian: Boolean = false
) {
    val bytesPerSample: Int get() = bitDepth / 8
    val bytesPerFrame: Int get() = bytesPerSample * channels

    /**
     * Converts a frame position to Milliseconds based on this spec.
     */
    fun framesToMs(frames: Long): Int {
        return (frames / (sampleRate / 1000.0)).toInt()
    }

    /**
     * Converts Milliseconds to a frame position based on this spec.
     */
    fun msToFrames(ms: Int): Long {
        return (ms * (sampleRate / 1000.0)).toLong()
    }
}