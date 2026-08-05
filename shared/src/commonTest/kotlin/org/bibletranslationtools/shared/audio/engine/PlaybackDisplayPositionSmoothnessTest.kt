package org.bibletranslationtools.shared.audio.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the SECOND smoothness property: the display clock advances at the audio rate every display
 * frame and only *slew-corrects* toward the player's real position — so a chunky/quantized player
 * position (HAL quanta: ~10–50 ms desktop, up to ~186 ms Android) produces a smooth per-frame
 * advance, never the position source's staircase. Reading getLocationInFrames() directly (what the
 * old 30 fps ticker effectively did) is exactly the staircase this replaces.
 */
class PlaybackDisplayPositionSmoothnessTest {

    private val SR = 44100
    private val FPS = 60
    private val frameNanos = 1_000_000_000L / FPS
    private val perFrame = SR.toDouble() / FPS // ≈ 735 source frames advanced per display frame

    /** Run [frames] display frames advancing a smooth true position; source is quantized to [quantum]. */
    private fun run(quantum: Int, frames: Int, reliable: () -> Boolean = { true }): List<Long> {
        var trueFrames = 0.0
        val clock = PlaybackDisplayPosition(
            // The player only reports on a coarse grid — the staircase we must smooth out.
            positionSource = { (floor(trueFrames / quantum) * quantum).toLong() },
            positionReliable = reliable
        )
        clock.sampleRate = SR
        clock.durationFrames = SR.toLong() * 1000
        clock.advancing = true
        clock.snapTo(0L)

        var nanos = 0L
        clock.onFrame(nanos) // first frame just anchors lastNanos
        val out = ArrayList<Long>(frames)
        repeat(frames) {
            nanos += frameNanos
            trueFrames += perFrame
            clock.onFrame(nanos)
            out.add(clock.displayFrame)
        }
        return out
    }

    @Test
    fun advancesMonotonicallyWithoutStaircaseJumps() {
        // Desktop: the player writes 1024 frames at a time, so it reports on a ~23 ms grid. That is inside
        // the lead budget, so the staircase is smoothed away completely.
        val displays = run(quantum = 1024, frames = 600) // 10 s
        for (i in 1 until displays.size) {
            val delta = displays[i] - displays[i - 1]
            assertTrue(delta >= 0, "position went backwards at frame $i (delta=$delta)")
            assertTrue(delta < perFrame * 3, "position jumped $delta at frame $i — not smooth")
        }
    }

    /**
     * Characterisation, and a known cost of following rather than simulating.
     *
     * Interpolation is capped at one update interval, and that cap is itself capped (50 ms) — past it the
     * position would be guessing more than following, which is the whole thing this exists not to do. So a
     * source coarser than the cap CANNOT be fully smoothed: Android's worst-case head-position quantum is
     * ~186 ms, and against that the position advances for 50 ms and then holds until the next report,
     * arriving in visible steps.
     *
     * That is the deliberate trade. The implementation this replaced smoothed a 186 ms quantum perfectly by
     * running its own clock, at the cost of being able to sit ahead of the sound — and on a compressed
     * waveform, being 186 ms ahead reads as broken in a way that stepping does not. What must still hold
     * even here is the part that matters: monotonic, and never leading.
     *
     * If the Android validation pass finds the stepping unacceptable, the fix is a finer position source
     * (smaller writes), NOT a larger budget.
     */
    @Test
    fun stepsRatherThanLeadsWhenTheSourceIsCoarserThanTheLeadBudget() {
        val quantum = 8192 // ~186 ms at 44.1k: Android's worst case
        val displays = run(quantum, frames = 600)
        var worstStep = 0L
        for (i in 1 until displays.size) {
            val delta = displays[i] - displays[i - 1]
            assertTrue(delta >= 0, "position went backwards at frame $i (delta=$delta)")
            worstStep = maxOf(worstStep, delta)
        }
        // Never ahead of what the source last said, by more than the budget.
        val budgetFrames = SR.toLong() * 50 / 1_000
        for (i in displays.indices) {
            val reported = (floor((i + 1) * perFrame / quantum) * quantum).toLong()
            assertTrue(
                displays[i] <= reported + budgetFrames,
                "position ${displays[i]} led the reported $reported by more than ${budgetFrames}f at frame $i"
            )
        }
        assertTrue(
            worstStep > perFrame * 3,
            "this test exists to pin the stepping; if it is now smooth the budget or the source changed " +
                "and the trade above should be re-read (worst step $worstStep)"
        )
    }

    @Test
    fun tracksTruePositionWithinABoundedLag() {
        // It must stay locked to reality — smooth AND not drifting away. After warmup the display
        // sits within ~one quantum + a slew margin of the true (smooth) position.
        val quantum = 2048
        var trueFrames = 0.0
        val clock = PlaybackDisplayPosition(
            positionSource = { (floor(trueFrames / quantum) * quantum).toLong() },
            positionReliable = { true }
        )
        clock.sampleRate = SR
        clock.durationFrames = SR.toLong() * 1000
        clock.advancing = true
        clock.snapTo(0L)
        var nanos = 0L
        clock.onFrame(nanos)
        repeat(600) {
            nanos += frameNanos
            trueFrames += perFrame
            clock.onFrame(nanos)
        }
        val lag = abs(trueFrames - clock.displayFrame.toDouble())
        assertTrue(lag < SR * 0.5, "display drifted ${lag / SR}s from true position")
    }

    @Test
    fun frozenWhenNotAdvancing() {
        // Paused: onFrame must not move the playhead at all (no drift while stopped).
        val clock = PlaybackDisplayPosition(positionSource = { 123_456L }, positionReliable = { true })
        clock.sampleRate = SR
        clock.durationFrames = SR.toLong() * 1000
        clock.advancing = false
        clock.snapTo(50_000L)
        var nanos = 0L
        repeat(120) { nanos += frameNanos; clock.onFrame(nanos) }
        assertTrue(clock.displayFrame == 50_000L, "paused clock drifted to ${clock.displayFrame}")
    }

    @Test
    fun seekSnapsImmediately() {
        // A seek (snapTo) jumps the display exactly, with no chase-back ramp.
        val clock = PlaybackDisplayPosition(positionSource = { 0L }, positionReliable = { false })
        clock.sampleRate = SR
        clock.durationFrames = SR.toLong() * 1000
        clock.snapTo(1_000_000L)
        assertTrue(clock.displayFrame == 1_000_000L)
    }

    @Test
    fun landsAndRestsExactlyOnTheEndAtCompletion() {
        // End-of-playback bulletproof. The audio-read trace proved the chapter's canonical length is
        // getDurationInFrames() (every frame reaches the sink), while the player's *reported*
        // completion position can stop ~one audio-buffer short (~860 frames / ~19 ms here). At
        // completion the VM snaps the clock to the canonical end and stops advancing; the display
        // must then sit EXACTLY on the end and never drift — even though the position source keeps
        // reporting the short value. This guards "the waveform reaches its drawn end when audio stops."
        val end = 425_984L
        val shortByBuffer = end - 860L // what the sink under-reports at completion
        val clock = PlaybackDisplayPosition(
            positionSource = { shortByBuffer },
            positionReliable = { true }
        )
        clock.sampleRate = SR
        clock.durationFrames = end
        // VM completion handling: snapTo(canonical end) + advancing=false.
        clock.advancing = false
        clock.snapTo(end)
        assertTrue(clock.displayFrame == end, "playhead should land on the end, was ${clock.displayFrame}")
        var nanos = 0L
        repeat(180) { nanos += frameNanos; clock.onFrame(nanos) }
        assertTrue(clock.displayFrame == end, "playhead must REST on the end (not drift to the short sink position), was ${clock.displayFrame}")
    }

    // The free-running-while-unreliable case that used to live here is gone with the implementation it
    // described. It asserted that the display kept advancing at 1.0x through the sink spin-up, which was
    // the least-bad option for a clock that had to be somewhere; the position now HOLDS instead, and
    // PlaybackDisplayPositionClampTest.itDrawsNothingUntilTheAudioHasActuallyStarted asserts that.
    // Holding is what removes the click-to-audio lead at source rather than correcting it afterwards.
}
