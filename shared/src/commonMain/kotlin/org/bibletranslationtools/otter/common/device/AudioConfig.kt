package org.bibletranslationtools.otter.common.device

/**
 * The audio engine's settings, in one place.
 *
 * Both fields were previously spread across the codebase in a way that made them un-changeable in
 * practice rather than merely inconvenient:
 *
 *  - **[spec]** was re-created as a bare `AudioSpec()` at a dozen call sites — every app entry point,
 *    every settings screen, every `recorder.start(...)`. Nothing was wrong with any one of them, but
 *    "record at 48k" meant finding and changing all twelve consistently, and a miss would show up as a
 *    device that discovers at one format and opens at another.
 *  - **[outputBufferMillis]** was a `JvmAudioSink` constructor argument that nothing ever passed, so
 *    the default was the only reachable value. It is the most consequential number in the playback
 *    path (see `JvmAudioSink.bufferMillis`), and it was effectively hard-coded.
 *
 * This is deliberately a plain value with defaults rather than something read from settings. Nothing
 * yet has the right to change these at runtime: a device change re-opens the hardware, but the format
 * a take plays at is the take's own, and the buffer is a property of the engine, not of the user. When
 * a settings screen does need to drive them, this is the type it should produce.
 *
 * Injected as a singleton (see `commonAudioModule`), which is what makes "end to end" true: discovery,
 * recording, and the hardware bridge all read the same instance.
 */
data class AudioConfig(
    /**
     * The format to record at and to discover devices with.
     *
     * NOT the format audio plays back at — a take plays at whatever format it was written in, and the
     * sink is opened from the reader's spec. This is the format for audio this app *creates*, and the
     * one a device must support to be offered in the first place.
     *
     * 48 kHz / 24-bit / stereo has been verified end to end on real hardware
     * (`RealAudioConfigTest`); changing this line is all it takes. It stays at 44.1/16/mono because
     * that is what existing takes are, and raising it is a product decision rather than a technical
     * one.
     */
    val spec: AudioSpec = AudioSpec(),

    /**
     * How much audio the output device buffers, in milliseconds.
     *
     * Bounds two things that are both felt as an unresponsive transport: how far the reported position
     * can lag the speaker, and how long a pause waits for an in-flight blocking `write()`. Left unset,
     * the mixer picks — measured at 500 ms on this hardware, for every format.
     */
    val outputBufferMillis: Int = DEFAULT_OUTPUT_BUFFER_MILLIS
) {
    init {
        val floor = minimumOutputBufferMillis(spec)
        require(outputBufferMillis >= floor) {
            "outputBufferMillis=$outputBufferMillis is below the floor of ${floor}ms for $spec. The " +
                "playback loop writes $WRITE_CHUNK_FRAMES frames at a time, and the device needs room " +
                "for one chunk in flight plus one draining; below that the line starves between writes. " +
                "Going lower is a matter of writing smaller chunks (AudioProcessor.inputBufferSize), " +
                "not of asking for a smaller buffer."
        }
    }

    companion object {
        /**
         * 50 ms: the smallest size that clears [minimumOutputBufferMillis] at both 44.1k (46 ms) and
         * 48k (43 ms), so the default holds if [spec] is raised.
         */
        const val DEFAULT_OUTPUT_BUFFER_MILLIS = 50

        /**
         * What the playback loop hands over per write — `DefaultAudioProcessor.inputBufferSize`. Stated
         * here because the buffer floor is derived from it and the two have to move together.
         */
        const val WRITE_CHUNK_FRAMES = 1024

        /** Twice one write chunk: one in flight, one draining. */
        fun minimumOutputBufferMillis(spec: AudioSpec): Int =
            (2 * WRITE_CHUNK_FRAMES * 1_000L / spec.sampleRate.coerceAtLeast(1)).toInt()
    }
}
