package org.bibletranslationtools.shared.ui.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the frame math behind "insert a recording at the playhead" (the recorder's playback-page
 * insert). The contract: the clip is spliced in at the requested timeline frame, everything after it
 * shifts later by exactly the clip length, and frames on BOTH sides keep pointing at the same source
 * audio they did before — nothing is re-sampled or re-addressed.
 */
class AudioTimelineInsertTest {

    private val base = FakeFrameValueSource(totalFrames = 1_000, id = "base")
    private val clip = FakeFrameValueSource(totalFrames = 100, id = "clip")

    private fun whole() = AudioTimeline.ofWholeSource(base)

    /** (sourceId, sourceFrame) that a timeline frame resolves to. */
    private fun resolve(t: AudioTimeline, frame: Int): Pair<String, Int> {
        val (segIndex, sourceFrame) = t.mapToSource(frame)
        return t.segments[segIndex].source.id to sourceFrame
    }

    @Test
    fun insertsIntoAnEmptyTimeline() {
        val result = AudioTimeline(emptyList()).insert(0, clip)
        assertEquals(100, result.totalFrames)
        assertEquals(1, result.segments.size)
        assertEquals("clip" to 0, resolve(result, 0))
    }

    @Test
    fun prependsAtFrameZero() {
        val result = whole().insert(0, clip)
        assertEquals(1_100, result.totalFrames)
        // The clip occupies [0,100); the original audio starts right after, from its frame 0.
        assertEquals("clip" to 0, resolve(result, 0))
        assertEquals("clip" to 99, resolve(result, 99))
        assertEquals("base" to 0, resolve(result, 100))
        assertEquals("base" to 999, resolve(result, 1_099))
    }

    @Test
    fun appendsAtTheEnd() {
        val result = whole().insert(1_000, clip)
        assertEquals(1_100, result.totalFrames)
        assertEquals("base" to 999, resolve(result, 999))
        assertEquals("clip" to 0, resolve(result, 1_000))
        assertEquals("clip" to 99, resolve(result, 1_099))
    }

    @Test
    fun splitsTheSegmentAtAMidPointAndShiftsTheTail() {
        val result = whole().insert(400, clip)
        assertEquals(1_100, result.totalFrames)
        // Head keeps its original addressing...
        assertEquals("base" to 399, resolve(result, 399))
        // ...the clip sits at the insertion point...
        assertEquals("clip" to 0, resolve(result, 400))
        assertEquals("clip" to 99, resolve(result, 499))
        // ...and the tail resumes exactly where the head left off, shifted by the clip length.
        assertEquals("base" to 400, resolve(result, 500))
        assertEquals("base" to 999, resolve(result, 1_099))
        assertEquals(3, result.segments.size)
    }

    @Test
    fun everyOriginalFrameIsStillReachableAfterAnInsert() {
        val result = whole().insert(400, clip)
        // Walk all 1000 original frames: before the clip they keep their frame, after it they shift
        // by exactly 100. Nothing is lost or duplicated.
        for (f in 0 until 1_000) {
            val timelineFrame = if (f < 400) f else f + 100
            assertEquals("base" to f, resolve(result, timelineFrame), "original frame $f moved")
        }
    }

    @Test
    fun insertsAtAnExactSegmentBoundaryWithoutSplitting() {
        // Cutting [200,300) leaves two segments meeting at timeline frame 200.
        val cut = whole().cut(200, 300)
        assertEquals(900, cut.totalFrames)
        val result = cut.insert(200, clip)
        assertEquals(1_000, result.totalFrames)
        assertEquals(3, result.segments.size) // head + clip + tail, no extra split
        assertEquals("base" to 199, resolve(result, 199))
        assertEquals("clip" to 0, resolve(result, 200))
        // The tail still starts after the cut (source frame 300), not 200.
        assertEquals("base" to 300, resolve(result, 300))
    }

    @Test
    fun insertingIntoAnAlreadyCutTimelinePreservesTheRemainingAudio() {
        val cut = whole().cut(500, 600) // drop source frames [500,600)
        val result = cut.insert(250, clip)
        assertEquals(1_000, result.totalFrames)
        // Cut audio stays gone: the frame right after the (shifted) cut point is source 600.
        assertEquals("base" to 499, resolve(result, 599))
        assertEquals("base" to 600, resolve(result, 600))
    }

    @Test
    fun anEmptyClipRangeIsANoOp() {
        val result = whole().insert(400, clip, IntRange.EMPTY)
        assertEquals(1_000, result.totalFrames)
        assertEquals(1, result.segments.size)
    }

    @Test
    fun insertsOnlyTheRequestedSliceOfTheClip() {
        val result = whole().insert(0, clip, 10 until 30)
        assertEquals(1_020, result.totalFrames)
        assertEquals("clip" to 10, resolve(result, 0))
        assertEquals("clip" to 29, resolve(result, 19))
        assertEquals("base" to 0, resolve(result, 20))
    }

    @Test
    fun clampsAnOutOfRangeInsertionPoint() {
        val negative = whole().insert(-50, clip)
        assertEquals("clip" to 0, resolve(negative, 0)) // clamped to a prepend
        val past = whole().insert(9_999, clip)
        assertEquals("clip" to 0, resolve(past, 1_000)) // clamped to an append
        assertTrue(negative.totalFrames == 1_100 && past.totalFrames == 1_100)
    }
}
