package org.bibletranslationtools.shared.audio.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clamp, on its own.
 *
 * [DeterministicDisplayDriftTest] runs this clock against real audio, but on a virtual clock the sink
 * reports a fresh position on every display frame — so the interpolation between reports never runs and
 * the result is a trivially exact 0..0. The interesting behaviour only appears when the player updates
 * more slowly than the display draws, which is the actual situation on hardware (~20-25 ms writes on
 * desktop, up to ~186 ms head-position quanta on Android).
 *
 * So the source here is a script, not a sink: the test decides exactly when the position moves, and
 * therefore knows exactly what "one update interval" is worth. Every assertion below is a restatement of
 * the one promise the class makes —
 *
 *     displayFrame ∈ [lastReported, lastReported + oneUpdateInterval]
 *
 * — under the four conditions where the old simulating clock got it wrong.
 */
class PlaybackDisplayPositionClampTest {

    @Test
    fun itInterpolatesBetweenChunkyReportsWithoutEverPassingTheNextOne() {
        val source = ScriptedSource()
        val clock = clockOver(source)
        clock.startAdvancing()

        // Position moves in 4-display-frame chunks: the classic "stutter if drawn raw" case.
        val drawn = mutableListOf<Long>()
        val reported = mutableListOf<Long>()
        repeat(TOTAL_FRAMES) { frame ->
            if (frame % REPORT_EVERY_FRAMES == 0) source.position += framesPer(REPORT_EVERY_FRAMES * FRAME_MILLIS)
            reported += source.position
            clock.onFrame(nanosAt(frame))
            drawn += clock.displayFrame
        }

        // It smooths: a staircase would repeat each value REPORT_EVERY_FRAMES times.
        val distinct = drawn.distinct().size
        assertTrue(
            distinct > TOTAL_FRAMES / 2,
            "the drawn position only took $distinct distinct values over $TOTAL_FRAMES frames — it is " +
                "staircasing on the reports instead of interpolating between them"
        )
        assertNeverLeads(drawn, reported, "chunky reports")
        assertNeverGoesBackwards(drawn, "chunky reports")
    }

    @Test
    fun itHoldsWhenTheDeviceStopsReportingInsteadOfWalkingAwayFromTheAudio() {
        val source = ScriptedSource()
        val clock = clockOver(source)
        clock.startAdvancing()

        // Settle into a steady report cadence so the clock has measured its update interval.
        repeat(WARMUP_FRAMES) { frame ->
            source.position += framesPer(FRAME_MILLIS)
            clock.onFrame(nanosAt(frame))
        }
        val atStall = clock.displayFrame

        // Then the device stalls: the position stops moving for 400 ms. A free-running integrator walks
        // 400 ms away from frozen audio and hard-snaps backwards when it returns (measured: 385 ms).
        val stallFrames = 400 / FRAME_MILLIS.toInt()
        repeat(stallFrames) { clock.onFrame(nanosAt(WARMUP_FRAMES + it)) }

        val crept = clock.displayFrame - atStall
        assertTrue(
            crept <= framesPer(MAX_LEAD_MILLIS),
            "the drawn position advanced ${millis(crept)}ms through a 400ms stall — it is running its own " +
                "clock, not following the audio"
        )
    }

    @Test
    fun itDrawsNothingUntilTheAudioHasActuallyStarted() {
        val source = ScriptedSource(position = 1_000L)
        var reliable = false
        val clock = PlaybackDisplayPosition({ source.position }, { reliable }).apply {
            sampleRate = SAMPLE_RATE
            durationFrames = SAMPLE_RATE * 10L
        }
        clock.snapTo(1_000L)
        clock.startAdvancing()

        // The gap between the click and the first sound is 25-130 ms measured. Through it the player
        // reports its WRITE cursor, which is ahead of anything audible.
        repeat(8) { frame ->
            source.position += framesPer(FRAME_MILLIS)
            clock.onFrame(nanosAt(frame))
        }
        assertEquals(
            1_000L,
            clock.displayFrame,
            "the playhead moved before the audio was audible — that lead is manufactured by the display, " +
                "not by latency"
        )

        // Sound starts; from here the position is the audible one and the clock follows it.
        reliable = true
        source.position = 1_000L
        clock.onFrame(nanosAt(8))
        assertEquals(1_000L, clock.displayFrame, "it should pick up exactly where the audio began")
    }

    @Test
    fun itHoldsThroughASeekInsteadOfDrawingTheStalePositionFirst() {
        val source = ScriptedSource(position = 400_000L)
        val clock = clockOver(source)
        clock.startAdvancing()
        clock.onFrame(nanosAt(0))

        // Seek backwards. The player's seek is async: the source keeps reporting the PRE-seek position
        // for a few frames, and following it would draw a jump forward and then back.
        clock.snapTo(100_000L)
        repeat(5) { frame ->
            source.position += framesPer(FRAME_MILLIS)
            clock.onFrame(nanosAt(1 + frame))
            assertEquals(
                100_000L,
                clock.displayFrame,
                "the display followed the stale pre-seek position on frame $frame"
            )
        }

        // The seek lands and normal following resumes.
        source.position = 100_000L
        repeat(5) { frame ->
            clock.onFrame(nanosAt(6 + frame))
            source.position += framesPer(FRAME_MILLIS)
        }
        assertTrue(
            clock.displayFrame in 100_000L..(100_000L + framesPer(6 * FRAME_MILLIS)),
            "after the seek landed the display should be following again, was ${clock.displayFrame}"
        )
    }

    private fun clockOver(source: ScriptedSource) =
        PlaybackDisplayPosition({ source.position }, { true }).apply {
            sampleRate = SAMPLE_RATE
            durationFrames = SAMPLE_RATE * 60L
        }

    /** The player's position, moved by the test rather than by a sink. */
    private class ScriptedSource(var position: Long = 0L)

    private fun assertNeverLeads(drawn: List<Long>, reported: List<Long>, case: String) {
        // The reported position is the audible frame; anything drawn beyond it plus one update interval
        // is the display getting ahead of the sound.
        val budget = framesPer(MAX_LEAD_MILLIS)
        val worst = drawn.indices.maxOf { drawn[it] - reported[it] }
        assertTrue(
            worst <= budget,
            "$case: drew ${millis(worst)}ms ahead of the reported position (budget ${MAX_LEAD_MILLIS}ms)"
        )
    }

    private fun assertNeverGoesBackwards(drawn: List<Long>, case: String) {
        val worst = drawn.zipWithNext().minOf { (a, b) -> b - a }
        assertTrue(worst >= 0, "$case: the playhead stepped backwards by ${-worst} frames")
    }

    private fun nanosAt(frame: Int) = START_NANOS + frame * FRAME_MILLIS * 1_000_000L

    private fun framesPer(millis: Long) = millis * SAMPLE_RATE / 1_000

    private fun millis(frames: Long) = frames * 1_000 / SAMPLE_RATE

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val FRAME_MILLIS = 16L
        const val START_NANOS = 1_000_000_000L
        const val TOTAL_FRAMES = 60
        const val REPORT_EVERY_FRAMES = 4
        const val WARMUP_FRAMES = 30

        /** Matches the ceiling in [PlaybackDisplayPosition]: one update plus the frame that observes it. */
        const val MAX_LEAD_MILLIS = 50L
    }
}
