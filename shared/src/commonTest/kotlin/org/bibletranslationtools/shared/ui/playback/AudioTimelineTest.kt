package org.bibletranslationtools.shared.ui.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTimelineTest {

    private fun whole(total: Int): AudioTimeline =
        AudioTimeline.ofWholeSource(FakePcmSourceTL(total))

    /** Flatten a timeline into the sequence of source frames it plays. */
    private fun frames(t: AudioTimeline): List<Int> {
        val out = mutableListOf<Int>()
        for (seg in t.segments) for (f in seg.sourceFrames) out.add(f)
        return out
    }

    @Test
    fun ofWholeSourceCoversEverything() {
        val t = whole(10)
        assertEquals(10, t.totalFrames)
        assertEquals((0..9).toList(), frames(t))
    }

    @Test
    fun cutAtZero() {
        val t = whole(10).cut(0, 3)
        assertEquals(7, t.totalFrames)
        assertEquals((3..9).toList(), frames(t))
    }

    @Test
    fun cutAtEof() {
        val t = whole(10).cut(7, 10)
        assertEquals(7, t.totalFrames)
        assertEquals((0..6).toList(), frames(t))
    }

    @Test
    fun cutInMiddle() {
        val t = whole(10).cut(3, 7)
        assertEquals(6, t.totalFrames)
        assertEquals(listOf(0, 1, 2, 7, 8, 9), frames(t))
    }

    @Test
    fun cutEverything() {
        val t = whole(10).cut(0, 10)
        assertEquals(0, t.totalFrames)
        assertTrue(frames(t).isEmpty())
    }

    @Test
    fun emptyOrReversedRangeIsNoOp() {
        val base = whole(10)
        assertEquals(frames(base), frames(base.cut(5, 5)))
        assertEquals(frames(base), frames(base.cut(8, 3)))
    }

    @Test
    fun clampsOutOfRangeInputs() {
        val t = whole(10).cut(-5, 100)
        assertEquals(0, t.totalFrames)
    }

    @Test
    fun overlappingCutsRemoveUnionOfSurvivors() {
        // First remove [2,5); on the resulting 7-frame timeline remove [1,4).
        val t = whole(10).cut(2, 5).cut(1, 4)
        // After first cut: 0,1,5,6,7,8,9. Remove relative [1,4) -> drops 1,5,6.
        assertEquals(listOf(0, 7, 8, 9), frames(t))
    }

    @Test
    fun adjacentCutsCombine() {
        // Cut [3,5) then, on the shortened timeline, the frames right after (relative 3
        // == source 5) — remove [3,5) again -> drops source 5,6.
        val t = whole(10).cut(3, 5).cut(3, 5)
        assertEquals(listOf(0, 1, 2, 7, 8, 9), frames(t))
    }

    @Test
    fun mapToSourceAcrossBoundaries() {
        val t = whole(10).cut(3, 7) // segments: [0..2], [7..9]
        assertEquals(0 to 0, t.mapToSource(0))
        assertEquals(0 to 2, t.mapToSource(2))
        // timeline frame 3 is the first frame of the second segment -> source 7
        assertEquals(1 to 7, t.mapToSource(3))
        assertEquals(1 to 9, t.mapToSource(5))
        // Past end clamps to last valid frame.
        assertEquals(1 to 9, t.mapToSource(999))
    }

    @Test
    fun readerReproducesTimelineFrames() {
        val t = whole(20).cut(5, 10).cut(9, 11) // survivors: 0-4,10-13,16-19
        val reader = TimelineAudioFileReader(t)
        reader.open()
        val bytes = ByteArray(t.totalFrames * reader.spec.bytesPerFrame)
        val read = reader.getPcmBuffer(bytes)
        reader.release()

        assertEquals(t.totalFrames, read / reader.spec.bytesPerFrame)
        assertEquals(frames(t), decode(bytes, read))
    }

    @Test
    fun readerSeekLandsInEditedSpace() {
        val t = whole(20).cut(5, 10) // survivors: 0-4, 10-19
        val reader = TimelineAudioFileReader(t)
        reader.open()
        reader.seek(5) // timeline frame 5 -> source frame 10
        val one = ByteArray(reader.spec.bytesPerFrame)
        val read = reader.getPcmBuffer(one)
        reader.release()
        assertEquals(listOf(10), decode(one, read))
    }

    private fun decode(bytes: ByteArray, byteCount: Int): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 1 < byteCount) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() shl 8
            out += (hi or lo).toShort().toInt()
            i += 2
        }
        return out
    }
}

/** Independent in-memory PcmSource (avoids cross-file test class collisions). */
internal class FakePcmSourceTL(
    override val totalFrames: Int,
    override val id: String = "fake-tl",
    override val sampleRate: Int = 44100
) : PcmSource {
    override fun openReader() = SequentialFrameReaderTL(totalFrames)
}

internal class SequentialFrameReaderTL(
    override val totalFrames: Int,
    override val spec: org.bibletranslationtools.otter.common.device.AudioSpec =
        org.bibletranslationtools.otter.common.device.AudioSpec(sampleRate = 44100, bitDepth = 16, channels = 1)
) : org.bibletranslationtools.otter.common.device.AudioFileReader {
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
