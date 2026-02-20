package org.bibletranslationtools.bttrecorder2.ui.playback

import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CutAwareAudioFileReaderTest {

    @Test
    fun readerSkipsCutRangesAndReturnsEditedTimeline() {
        val base = SequentialFrameReader(totalFrames = 20)
        val cutAware = CutAwareAudioFileReader(
            delegate = base,
            cutRanges = listOf(
                WaveEditSession.CutRange(5, 10),
                WaveEditSession.CutRange(14, 16)
            )
        )

        cutAware.open()
        val bytes = ByteArray(cutAware.totalFrames * cutAware.spec.bytesPerFrame)
        val read = cutAware.getPcmBuffer(bytes)
        cutAware.release()

        assertEquals(13, cutAware.totalFrames)
        assertEquals(13 * 2, read)
        assertContentEquals(
            listOf(0, 1, 2, 3, 4, 10, 11, 12, 13, 16, 17, 18, 19),
            decodePcmFrames(bytes, read)
        )
    }

    @Test
    fun seekUsesEditedFrameSpace() {
        val base = SequentialFrameReader(totalFrames = 20)
        val cutAware = CutAwareAudioFileReader(
            delegate = base,
            cutRanges = listOf(
                WaveEditSession.CutRange(5, 10),
                WaveEditSession.CutRange(14, 16)
            )
        )

        cutAware.open()
        cutAware.seek(9)
        val oneFrame = ByteArray(2)
        val read = cutAware.getPcmBuffer(oneFrame)
        cutAware.release()

        assertEquals(2, read)
        assertContentEquals(listOf(16), decodePcmFrames(oneFrame, read))
        assertTrue(cutAware.framePosition >= 9)
    }

    private fun decodePcmFrames(bytes: ByteArray, byteCount: Int): List<Int> {
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

private class SequentialFrameReader(
    override val totalFrames: Int,
    override val spec: AudioSpec = AudioSpec(sampleRate = 44100, bitDepth = 16, channels = 1)
) : AudioFileReader {
    override var framePosition: Int = 0
    private var opened = false

    override fun open() {
        opened = true
    }

    override fun hasRemaining(): Boolean {
        return opened && framePosition < totalFrames
    }

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

    override fun seek(frame: Long) {
        framePosition = frame.toInt().coerceIn(0, totalFrames)
    }

    override fun release() {
        opened = false
    }
}
