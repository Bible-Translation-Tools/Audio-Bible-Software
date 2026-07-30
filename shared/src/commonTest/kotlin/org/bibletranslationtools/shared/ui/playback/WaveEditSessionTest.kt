package org.bibletranslationtools.shared.ui.playback

import org.bibletranslationtools.otter.common.device.AudioFileReader
import org.bibletranslationtools.otter.common.device.AudioSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** In-memory PcmSource for tests — no disk, sequential-frame reader. */
private class FakePcmSource(
    override val totalFrames: Int,
    override val id: String = "fake",
    override val sampleRate: Int = 44100
) : PcmSource {
    override fun openReader(): AudioFileReader = SequentialFrameReader(totalFrames)
}

/** Reader that emits frame N as the little-endian short N (channel 0, 16-bit mono). */
private class SequentialFrameReader(
    override val totalFrames: Int,
    override val spec: AudioSpec = AudioSpec(sampleRate = 44100, bitDepth = 16, channels = 1)
) : AudioFileReader {
    override var framePosition: Int = 0
    private var opened = false

    override fun open() { opened = true }
    override fun hasRemaining(): Boolean = opened && framePosition < totalFrames

    override fun getPcmBuffer(bytes: ByteArray): Int {
        if (!hasRemaining()) return 0
        val frameBytes = spec.bytesPerFrame
        val maxFrames = bytes.size / frameBytes
        val framesToRead = minOf(maxFrames, totalFrames - framePosition)
        var write = 0
        for (i in 0 until framesToRead) {
            val value = (framePosition + i).toShort()
            bytes[write] = (value.toInt() and 0xFF).toByte()
            bytes[write + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
            write += frameBytes
        }
        framePosition += framesToRead
        return framesToRead * frameBytes
    }

    override fun seek(frame: Long) { framePosition = frame.toInt().coerceIn(0, totalFrames) }
    override fun release() { opened = false }
}

class WaveEditSessionTest {

    @Test
    fun cutRelativeRemovesSurvivingFramesAndMapsSpaces() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 100))

        // Cut relative [10, 20) — first edit, so relative == absolute here.
        assertTrue(session.cutRelative(10, 20))
        assertEquals(90, session.editedTotalFrames)
        // A second cut expressed in the (now shorter) edited timeline.
        assertTrue(session.cutRelative(10, 30))
        assertEquals(70, session.editedTotalFrames)

        // Frames that were removed report as removed; survivors do not.
        assertTrue(session.isFrameRemoved(15))
        assertFalse(session.isFrameRemoved(5))
    }

    @Test
    fun noOpCutReturnsFalseAndKeepsHistoryClean() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 50))
        assertFalse(session.cutRelative(10, 10)) // empty range
        assertFalse(session.canUndo())
        assertFalse(session.hasEdits())
    }

    @Test
    fun undoRedoRestoresCutHistory() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 50))
        session.cutRelative(5, 10)
        session.cutRelative(15, 25) // relative on the 45-frame timeline

        assertTrue(session.hasEdits())
        assertTrue(session.canUndo())
        assertEquals(35, session.editedTotalFrames)

        assertTrue(session.undo())
        assertEquals(45, session.editedTotalFrames)

        assertTrue(session.undo())
        assertFalse(session.hasEdits())
        assertEquals(50, session.editedTotalFrames)

        assertTrue(session.redo())
        assertTrue(session.redo())
        assertEquals(35, session.editedTotalFrames)
    }

    @Test
    fun clearAllEditsRestoresWholeSourceAndIsUndoable() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 40))
        session.cutRelative(10, 20)
        assertEquals(30, session.editedTotalFrames)
        session.clearAllEdits()
        assertEquals(40, session.editedTotalFrames)
        assertFalse(session.hasEdits())
        assertTrue(session.undo())
        assertEquals(30, session.editedTotalFrames)
    }

    /**
     * Equivalence oracle: absoluteToRelative / isFrameRemoved must match the OLD
     * cut-range algorithm exactly (marker remapping depends on this). We reproduce a
     * cut sequence via relative cuts, derive the resulting removed ranges from the
     * session, then verify the session's mapping equals the reference computation over
     * those ranges for every source frame.
     */
    @Test
    fun absoluteMappingMatchesOldCutRangeLogic() {
        val total = 100
        val session = WaveEditSession(FakePcmSource(totalFrames = total))
        session.cutRelative(20, 30) // removes source [20,30)
        session.cutRelative(40, 50) // removes source [50,60) on the shortened timeline

        val ranges = session.rangesSnapshot().map { it.start to it.endExclusive }

        for (frame in 0..total) {
            assertEquals(
                oldIsFrameRemoved(frame, ranges),
                session.isFrameRemoved(frame),
                "isFrameRemoved mismatch at $frame"
            )
            assertEquals(
                oldAbsoluteToRelative(frame, total, ranges),
                session.absoluteToRelative(frame),
                "absoluteToRelative mismatch at $frame"
            )
        }
    }

    // --- Insert (playback-page insert recording) ---

    @Test
    fun insertRelativeSplicesTheClipAndLengthensTheTimeline() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 100, id = "take"))
        val clip = FakePcmSource(totalFrames = 30, id = "clip")

        assertTrue(session.insertRelative(40, clip))
        assertEquals(130, session.editedTotalFrames)
        assertTrue(session.hasEdits())
        assertTrue(session.canUndo())
    }

    @Test
    fun insertedClipSurvivesUndoRedo() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 100, id = "take"))
        val clip = FakePcmSource(totalFrames = 30, id = "clip")
        session.insertRelative(40, clip)

        assertTrue(session.undo())
        assertEquals(100, session.editedTotalFrames)
        assertFalse(session.hasEdits()) // back to the untouched take

        assertTrue(session.redo())
        assertEquals(130, session.editedTotalFrames)
        assertTrue(session.timeline().segments.any { it.source.id == "clip" })
    }

    @Test
    fun emptyClipIsRejectedAndKeepsHistoryClean() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 100, id = "take"))
        assertFalse(session.insertRelative(10, FakePcmSource(totalFrames = 0, id = "empty")))
        assertFalse(session.canUndo())
        assertFalse(session.hasEdits())
    }

    /**
     * A cut followed by an equal-length insert leaves the frame count unchanged but the AUDIO very
     * much changed — the old length-based hasEdits() reported "clean" here and the Save button stayed
     * disabled, losing the edit.
     */
    @Test
    fun cutThenEqualLengthInsertStillCountsAsEdited() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 100, id = "take"))
        session.cutRelative(10, 40) // -30 frames
        session.insertRelative(10, FakePcmSource(totalFrames = 30, id = "clip")) // +30 frames

        assertEquals(100, session.editedTotalFrames) // same length as the original
        assertTrue(session.hasEdits(), "cut + equal-length insert must report as edited")
    }

    /** Inserted segments belong to another source, so they must not be read as coverage of the take. */
    @Test
    fun insertDoesNotCorruptRemovedRangesOfTheOriginalTake() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 100, id = "take"))
        session.cutRelative(10, 20) // drop take frames [10,20)
        session.insertRelative(10, FakePcmSource(totalFrames = 50, id = "clip"))

        // The only missing part of the ORIGINAL take is still [10,20).
        val removed = session.rangesSnapshot()
        assertEquals(1, removed.size)
        assertEquals(10, removed.first().start)
        assertEquals(20, removed.first().endExclusive)
        assertTrue(session.isFrameRemoved(15))
        assertFalse(session.isFrameRemoved(25))
    }

    /** Take frames after an inserted clip must map further down the timeline — this is what keeps
     *  verse markers pinned to their audio after an insert. */
    @Test
    fun absoluteToRelativeShiftsTakeFramesThatFollowAnInsertedClip() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 100, id = "take"))
        session.insertRelative(40, FakePcmSource(totalFrames = 30, id = "clip"))

        assertEquals(10, session.absoluteToRelative(10)) // before the insert: unchanged
        assertEquals(70, session.absoluteToRelative(40)) // at/after it: shifted by the clip length
        assertEquals(129, session.absoluteToRelative(99))
    }

    /** A timeline position inside the inserted clip has no take frame; report the splice boundary. */
    @Test
    fun relativeToAbsoluteInsideAnInsertedClipReportsTheSpliceBoundary() {
        val session = WaveEditSession(FakePcmSource(totalFrames = 100, id = "take"))
        session.insertRelative(40, FakePcmSource(totalFrames = 30, id = "clip"))

        assertEquals(39, session.relativeToAbsolute(39)) // last take frame before the clip
        assertEquals(40, session.relativeToAbsolute(50)) // inside the clip → boundary
        assertEquals(40, session.relativeToAbsolute(70)) // first take frame after the clip
    }

    // --- Reference implementations copied from the original WaveEditSession ---

    private fun oldIsFrameRemoved(frame: Int, ranges: List<Pair<Int, Int>>): Boolean =
        ranges.any { frame in it.first until it.second }

    private fun oldAbsoluteToRelative(frame: Int, total: Int, ranges: List<Pair<Int, Int>>): Int {
        val clamped = frame.coerceIn(0, total)
        val removed = ranges.sumOf { it.second - it.first }
        val editedTotal = (total - removed).coerceAtLeast(0)
        var removedBefore = 0
        for ((start, end) in ranges) {
            if (clamped <= start) break
            val clippedEnd = minOf(clamped, end)
            if (clippedEnd > start) removedBefore += (clippedEnd - start)
        }
        return (clamped - removedBefore).coerceIn(0, editedTotal)
    }
}
