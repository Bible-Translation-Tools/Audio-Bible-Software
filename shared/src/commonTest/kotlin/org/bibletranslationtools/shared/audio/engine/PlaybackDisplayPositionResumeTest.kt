package org.bibletranslationtools.shared.audio.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replaying a take after it has played through once.
 *
 * On completion a host parks the display at the end (`snapTo(durationFrames)`). Pressing play rewinds
 * the player to 0 — `AudioPlayerConnection.play()` seeks to 0 when the worker is at or past
 * `totalFrames` — but on its own control dispatcher, so nothing informs the display. The reported
 * symptom was that the waveform sat at the end of the file with the elapsed time frozen for about two
 * seconds while audio played from the beginning, then jumped.
 *
 * [PlaybackDisplayPosition.startAdvancing] closes that gap. The test that documents why it is needed is
 * [staysStuckAtTheEndWhenAdvancingIsSetDirectly] — keep it: it is the trap, and it is one keystroke
 * away at five call sites.
 */
class PlaybackDisplayPositionResumeTest {

    private val SR = 44100
    private val FPS = 60
    private val frameNanos = 1_000_000_000L / FPS
    private val perFrame = SR.toDouble() / FPS
    private val duration = SR.toLong() * 60 // a one-minute take

    /**
     * A clock parked at the end, as `AudioPlayerEvent.Complete` leaves it, with a source that reports
     * playback running from [sourceStart] onward — the player having rewound.
     */
    private class Harness(sampleRate: Int, duration: Long, sourceStart: Double) {
        var truePos = sourceStart
        val clock = PlaybackDisplayPosition(
            positionSource = { truePos.toLong() },
            positionReliable = { true }
        ).apply {
            this.sampleRate = sampleRate
            this.durationFrames = duration
        }
    }

    private fun Harness.runFrames(count: Int, startNanos: Long = 0L): Long {
        var nanos = startNanos
        clock.onFrame(nanos) // anchors lastNanos; no advance
        repeat(count) {
            nanos += frameNanos
            truePos += perFrame
            clock.onFrame(nanos)
        }
        return nanos
    }

    // ── the fix ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `startAdvancing follows the rewound player immediately`() {
        val h = Harness(SR, duration, sourceStart = 0.0)
        h.clock.snapTo(duration) // Complete parked it at the end
        assertEquals(duration, h.clock.displayFrame)

        h.clock.startAdvancing()
        // Not synchronously. The old clock had to pre-position itself here, because it would otherwise
        // free-run from the end while the source reported 0 — an error far outside its slew band, which it
        // would sit on until a 2 s valve force-accepted it. This one has nothing to guess: startAdvancing
        // drops the anchor, and the rewind arrives as an ordinary large backward move in the reported
        // position on the very next frame.
        h.clock.onFrame(0L)
        assertEquals(0L, h.clock.displayFrame, "the display should rewind with the player, within a frame")

        h.runFrames(30) // half a second

        assertTrue(
            abs(h.clock.displayFrame - h.truePos.toLong()) < SR / 10,
            "display ${h.clock.displayFrame} should track the player ${h.truePos.toLong()}"
        )
    }

    /** The elapsed-time readout reads displayFrame, so a parked display is also a frozen timer. */
    @Test
    fun `the display advances during the first half second of a replay`() {
        val h = Harness(SR, duration, sourceStart = 0.0)
        h.clock.snapTo(duration)
        h.clock.startAdvancing()

        h.runFrames(30)

        assertTrue(
            h.clock.displayFrame in 1 until duration / 2,
            "display should be near the start of the replay, was ${h.clock.displayFrame}"
        )
    }

    /**
     * The trap this exists to prevent. Setting [PlaybackDisplayPosition.advancing] directly leaves the
     * display parked at the end: the source reports ~0, so the error is far outside the slew band, and
     * while unsettled an out-of-band error is free-run rather than corrected. Nothing moves until the
     * 2 s convergence valve force-accepts the source.
     */
    @Test
    fun staysStuckAtTheEndWhenAdvancingIsSetDirectly() {
        val h = Harness(SR, duration, sourceStart = 0.0)
        h.clock.snapTo(duration)
        h.clock.advancing = true // what every host used to do

        h.runFrames(30)

        assertEquals(
            duration,
            h.clock.displayFrame,
            "without startAdvancing the display stays parked at the end — the reported bug"
        )
    }

    /** And it does eventually unstick, via the safety valve — which is the 2 s delay users saw. */
    @Test
    fun `the safety valve is what unsticks a directly-advanced clock`() {
        val h = Harness(SR, duration, sourceStart = 0.0)
        h.clock.snapTo(duration)
        h.clock.advancing = true

        h.runFrames(count = 60 * 3) // three seconds, past the 2 s valve

        assertTrue(
            h.clock.displayFrame < duration / 2,
            "the valve should have accepted the source by now, was ${h.clock.displayFrame}"
        )
    }

    // ── resuming mid-file must not rewind ────────────────────────────────────────────────

    /**
     * Pause/resume goes through the same call, so the rewind must be conditional on actually being at
     * the end — otherwise every resume would jump the display to zero.
     */
    @Test
    fun `startAdvancing does not rewind when resuming mid-file`() {
        val midpoint = duration / 2
        val h = Harness(SR, duration, sourceStart = midpoint.toDouble())
        h.clock.snapTo(midpoint) // paused here

        h.clock.startAdvancing()

        assertEquals(midpoint, h.clock.displayFrame, "a mid-file resume must not rewind")
    }

    /** A clock with no take loaded has nothing to rewind to and must not divide by a zero duration. */
    @Test
    fun `startAdvancing is safe with no duration`() {
        val h = Harness(SR, duration = 0, sourceStart = 0.0)

        h.clock.startAdvancing()

        assertEquals(0L, h.clock.displayFrame)
        assertTrue(h.clock.advancing)
    }
}
