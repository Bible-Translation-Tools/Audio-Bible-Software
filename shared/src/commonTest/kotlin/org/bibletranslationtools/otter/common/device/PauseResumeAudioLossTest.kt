package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pausing and resuming must not lose audio. Every frame between where a take started and where it got to
 * has to reach the speaker, however many times the transport was toggled on the way.
 *
 * This is the one property that no amount of position arithmetic can substitute for. `pause()` stops and
 * **flushes** the sink, which throws away everything the player had already read from the file and queued
 * — up to a bufferful. Resuming from where playback actually *got to* replays it; resuming from where the
 * player had *read to* silently drops it. Both leave a perfectly plausible position behind, so only the
 * audio itself tells you which happened, which is what [BufferedAudioSink.skippedContentFrames] measures
 * via the content index [IndexedAudioFileReader] stamps into every buffer.
 */
class PauseResumeAudioLossTest {

    /**
     * Deliberate spam: pause and play as fast as the transport will take it, which is how a user finds
     * this. Each pair of control calls is launched onto the control dispatcher without waiting for the
     * previous to land, exactly as a double-tapping user produces them.
     */
    @Test
    fun spammingPauseAndPlayDoesNotSwallowAudio() = runTest {
        withBufferedTransport(takeFrames = SAMPLE_RATE * 20, bufferFrames = BUFFER_FRAMES) {
            val connection = connection()
            connection.load(take)
            awaitEvent("Load")
            connection.play()
            awaitEvent("Play")
            delay(400)

            repeat(CYCLES) {
                connection.pause()
                delay(TOGGLE_MILLIS)
                connection.play()
                delay(TOGGLE_MILLIS)
            }

            // Let whatever is still queued drain so the tally covers everything that was going to be heard.
            delay(800)

            val skippedMillis = bufferedSink.skippedContentFrames * 1_000 / SAMPLE_RATE
            assertTrue(
                bufferedSink.audibleContentFrame > SAMPLE_RATE / 2,
                "the take must actually have played for this to mean anything " +
                    "(audible frame ${bufferedSink.audibleContentFrame})"
            )
            assertTrue(
                skippedMillis <= TOLERANCE_MILLIS,
                "$CYCLES pause/play toggles swallowed ${skippedMillis}ms of audio that was never played " +
                    "to anyone (${bufferedSink.skippedContentFrames} frames). Pausing must not lose the " +
                    "audio it flushes out of the hardware buffer.\n  ${bufferedSink.diagnose()}"
            )
        }
    }

    /** The same property at a human pace, to separate "loses audio" from "loses audio only under a race". */
    @Test
    fun pausingAndResumingAtAHumanPaceDoesNotSwallowAudio() = runTest {
        withBufferedTransport(takeFrames = SAMPLE_RATE * 20, bufferFrames = BUFFER_FRAMES) {
            val connection = connection()
            connection.load(take)
            awaitEvent("Load")
            connection.play()
            awaitEvent("Play")
            delay(400)

            repeat(CYCLES) {
                connection.pause()
                awaitEvent("Pause")
                delay(150)
                connection.play()
                awaitEvent("Play")
                delay(300)
            }
            delay(800)

            val skippedMillis = bufferedSink.skippedContentFrames * 1_000 / SAMPLE_RATE
            assertTrue(
                skippedMillis <= TOLERANCE_MILLIS,
                "$CYCLES unhurried pause/resume cycles swallowed ${skippedMillis}ms of audio " +
                    "(${bufferedSink.skippedContentFrames} frames)\n  ${bufferedSink.diagnose()}"
            )
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BUFFER_FRAMES = 44_100 * 4 / 10 // ~400ms, the measured macOS queue depth
        const val CYCLES = 8
        const val TOGGLE_MILLIS = 60L

        /** One buffer's worth of slack: the boundary frame itself may land either side of a flush. */
        const val TOLERANCE_MILLIS = 30
    }
}
