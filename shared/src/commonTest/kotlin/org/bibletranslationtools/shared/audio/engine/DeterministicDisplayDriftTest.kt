package org.bibletranslationtools.shared.audio.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.device.AudioTransportHarness
import org.bibletranslationtools.otter.common.device.withBufferedTransport
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Is the drawn playhead on the frame you can hear? Measured exactly, on one clock.
 *
 * [PlaybackDisplayDriftTest] asks the same question in real time, which makes it a race: the sink drains on
 * the wall clock, the display advances on the wall clock, and the answer wobbles by however much the
 * machine was busy. Here both read the **same** virtual clock, so a step of 16ms drains exactly 16ms of
 * audio and advances the display by exactly one frame. The comparison is then arithmetic, and a failure is
 * a real offset rather than a scheduling hiccup.
 *
 * Ground truth is [org.bibletranslationtools.otter.common.device.BufferedAudioSink.audibleContentFrame] —
 * the take frame currently under the play cursor, decoded from the content index stamped into every buffer.
 * Nothing here infers position from frame counts.
 *
 * The three assertions are the criteria [PlaybackDisplayPosition] was chosen against, written down before
 * it was. They are stated as properties rather than tolerances on purpose: the implementation this
 * replaced could satisfy the third on a good run while structurally unable to promise the first.
 *
 * The one thing that cannot be virtualised is the writer: the playback loop is a real coroutine blocking in
 * `write()`, so after each time step it needs a moment of real time to refill the queue. [settle] waits for
 * that on an observable condition rather than a guess, which keeps the *content* deterministic even though
 * the thread scheduling is not.
 */
class DeterministicDisplayDriftTest {

    @Test
    fun theDrawnPositionMatchesTheAudibleFrameStepForStep() = runTest {
        val clock = VirtualClock()
        withBufferedTransport(
            takeFrames = SAMPLE_RATE * 10,
            bufferFrames = BUFFER_FRAMES,
            nanoTime = clock::nanos
        ) {
            val connection = connection()
            val display = PlaybackDisplayPosition(
                positionSource = { connection.getLocationInFrames().toLong() },
                positionReliable = { connection.isPositionReliable() }
            ).apply {
                sampleRate = SAMPLE_RATE
                durationFrames = takeFrames.toLong()
            }

            connection.load(take)
            awaitEvent("Load")
            connection.play()
            awaitEvent("Play")
            display.startAdvancing()

            // Fill the queue before comparing: until the hardware has audio, there is no audible frame to
            // compare against and the display has nothing to be right or wrong about.
            repeat(PRIMING_STEPS) { step(clock, display) }

            val trace = Trace()
            repeat(MEASURED_STEPS) {
                step(clock, display)
                val audible = bufferedSink.audibleContentFrame
                if (audible > 0) trace.record(display.displayFrame, audible)
            }

            println("[DRIFT] ${trace.summary()}")

            assertTrue(
                trace.samples > MEASURED_STEPS / 2,
                "the comparison must actually have run: only ${trace.samples} of $MEASURED_STEPS " +
                    "steps had an audible frame to compare against"
            )

            // Criterion 1: never ahead of the sound by more than one position update.
            assertTrue(
                trace.maxLeadMillis() <= MAX_LEAD_MILLIS,
                "the drawn playhead led the audible frame by ${trace.maxLeadMillis()}ms " +
                    "(budget ${MAX_LEAD_MILLIS}ms). Leading is the direction that reads as broken: the " +
                    "waveform has already scrolled past the sound. ${trace.summary()}"
            )

            // Criterion 2: no backwards steps while playback runs forward.
            assertTrue(
                trace.backwardSteps == 0,
                "the drawn playhead stepped backwards ${trace.backwardSteps} times during forward " +
                    "playback (worst ${trace.worstBackwardStep}f). ${trace.summary()}"
            )

            // Criterion 3: the lag it trades for that stays inside a frame or two.
            assertTrue(
                trace.worstMillis() < TOLERANCE_MILLIS,
                "the drawn playhead was ${trace.worstMillis()}ms away from the audible frame at " +
                    "worst. On one clock this is not jitter — it is the position accounting being wrong " +
                    "by that much. ${trace.summary()}"
            )
        }
    }

    /** One display frame: advance the shared clock, let the writer catch up, then drive the display. */
    private suspend fun AudioTransportHarness.step(clock: VirtualClock, display: PlaybackDisplayPosition) {
        clock.advanceMillis(FRAME_MILLIS)
        settle()
        display.onFrame(clock.nanos())
    }

    /**
     * Waits for the playback loop to finish reacting to the time step. The queue is the observable: once
     * the writer has refilled it, or stops making progress, the step is complete.
     */
    private suspend fun AudioTransportHarness.settle() {
        var previousWrites = -1
        repeat(SETTLE_POLLS) {
            val writes = sink.writes.value
            if (writes == previousWrites) return
            previousWrites = writes
            delay(1)
        }
    }

    /** Everything worth knowing about the run, accumulated as it goes. */
    private class Trace {
        private val offsets = mutableListOf<Long>()
        private var previousFrame = -1L
        var backwardSteps = 0
            private set
        var worstBackwardStep = 0L
            private set

        val samples: Int get() = offsets.size

        fun record(displayFrame: Long, audibleFrame: Long) {
            offsets += displayFrame - audibleFrame
            if (previousFrame >= 0 && displayFrame < previousFrame) {
                backwardSteps++
                worstBackwardStep = maxOf(worstBackwardStep, previousFrame - displayFrame)
            }
            previousFrame = displayFrame
        }

        /** Positive offsets only: how far AHEAD of the sound the playhead was drawn, at worst. */
        fun maxLeadMillis(): Long = millis(offsets.maxOrNull()?.coerceAtLeast(0L) ?: 0L)

        fun worstMillis(): Long = millis(offsets.maxOfOrNull { abs(it) } ?: 0L)

        fun summary(): String {
            val min = offsets.minOrNull() ?: 0L
            val max = offsets.maxOrNull() ?: 0L
            return "over $samples steps: lag=${millis(-min)}ms lead=${millis(max.coerceAtLeast(0L))}ms " +
                "worst=${worstMillis()}ms backSteps=$backwardSteps(worst=${worstBackwardStep}f) " +
                "offsets=$min..$max frames"
        }

        private fun millis(frames: Long) = frames * 1_000 / SAMPLE_RATE
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BUFFER_FRAMES = 44_100 / 10 // 100ms
        const val FRAME_MILLIS = 16L
        const val PRIMING_STEPS = 20
        const val MEASURED_STEPS = 120
        const val SETTLE_POLLS = 40

        /** The hardware's own floor is 6-11ms (measured). Anything past this is accounting, not physics. */
        const val TOLERANCE_MILLIS = 60

        /**
         * One position update plus the display frame that observes it. The clamp in
         * [PlaybackDisplayPosition] is what enforces this; the number is here so a change to that clamp
         * has to be argued for.
         */
        const val MAX_LEAD_MILLIS = 50
    }
}

/** A clock the test owns. Nothing advances unless the test says so. */
private class VirtualClock(private var value: Long = 1_000_000_000L) {
    fun nanos(): Long = value

    fun advanceMillis(millis: Long) {
        value += millis * 1_000_000
    }
}
