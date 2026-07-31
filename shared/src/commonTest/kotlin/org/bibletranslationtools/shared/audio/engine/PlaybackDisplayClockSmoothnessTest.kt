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
class PlaybackDisplayClockSmoothnessTest {

    private val SR = 44100
    private val FPS = 60
    private val frameNanos = 1_000_000_000L / FPS
    private val perFrame = SR.toDouble() / FPS // ≈ 735 source frames advanced per display frame

    /** Run [frames] display frames advancing a smooth true position; source is quantized to [quantum]. */
    private fun run(quantum: Int, frames: Int, reliable: () -> Boolean = { true }): List<Long> {
        var trueFrames = 0.0
        val clock = PlaybackDisplayClock(
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
        // Android worst case: ~186 ms quantum ≈ 8200 frames. The raw source jumps 8200 at once every
        // ~11 display frames; the clock must instead advance ~735/frame and never jump a full quantum.
        val quantum = 8192
        val displays = run(quantum, frames = 600) // 10 s
        for (i in 1 until displays.size) {
            val delta = displays[i] - displays[i - 1]
            assertTrue(delta >= 0, "clock went backwards at frame $i (delta=$delta)")
            // Smooth: within a few × the ideal per-frame step, and FAR below one source quantum.
            assertTrue(delta < perFrame * 3, "clock jumped $delta at frame $i (quantum=$quantum) — not smooth")
        }
    }

    @Test
    fun tracksTruePositionWithinABoundedLag() {
        // It must stay locked to reality — smooth AND not drifting away. After warmup the display
        // sits within ~one quantum + a slew margin of the true (smooth) position.
        val quantum = 2048
        var trueFrames = 0.0
        val clock = PlaybackDisplayClock(
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
        val clock = PlaybackDisplayClock(positionSource = { 123_456L }, positionReliable = { true })
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
        val clock = PlaybackDisplayClock(positionSource = { 0L }, positionReliable = { false })
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
        val clock = PlaybackDisplayClock(
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

    @Test
    fun freeRunsWhilePositionUnreliable() {
        // During the sink spin-up transient the source lies (write cursor, far ahead). While
        // positionReliable is false the clock must free-run at exactly 1.0× from the last snapTo,
        // NOT jump to the lie — the guard that a naive {isPlaying} gate loses.
        val clock = PlaybackDisplayClock(
            positionSource = { 5_000_000L }, // an absurd "ahead" lie
            positionReliable = { false }
        )
        clock.sampleRate = SR
        clock.durationFrames = SR.toLong() * 1000
        clock.advancing = true
        clock.snapTo(0L)
        var nanos = 0L
        clock.onFrame(nanos)
        repeat(60) { nanos += frameNanos; clock.onFrame(nanos) }
        // ~1 s of free-run ≈ SR frames, NOT the 5,000,000 lie.
        val expected = SR.toLong()
        assertTrue(abs(clock.displayFrame - expected) < SR * 0.1, "free-run drifted to ${clock.displayFrame}")
    }
}
