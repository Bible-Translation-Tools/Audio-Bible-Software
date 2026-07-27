package org.bibletranslationtools.shared.ui.playback

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the two rendering properties that make a scrolling waveform SMOOTH — and that the
 * narration `AudioScene` renderer lacks (its buckets re-bin from a floating phase every frame,
 * so the same audio feature lands in a different column each render → visible crawl/jitter).
 *
 * The fake source emits sample value == absolute frame index, so a bucket b of the peak cache
 * holds min = b*64, max = b*64+63 exactly, and any binning drift is detectable to the frame.
 *
 * Property 1 (correctness): a window column reflects the exact min/max of the source frames it
 * covers on the ABSOLUTE grid (column k ⇒ frames [floor(k*fpp), floor((k+1)*fpp))).
 *
 * Property 2 (frame-stability / anti-crawl): the value drawn for a fixed ABSOLUTE column is
 * identical no matter where the visible window starts. Scrolling only changes which columns are
 * visible, never a column's content — so features glide by pixel, they never re-shuffle.
 */
class WaveformWindowStabilityTest {

    private fun cacheOf(total: Int): Pair<AudioTimeline, (PcmSource) -> WaveformPeakCache?> {
        val source = FakeFrameValueSource(total)
        val cache = WaveformPeakCache(source.totalFrames)
        runBlocking { buildPeakCache(source, cache, EmptyCoroutineContext) }
        return AudioTimeline.ofWholeSource(source) to { cache }
    }

    /** Read one full window into fresh arrays (like a draw frame would). */
    private fun window(
        tl: AudioTimeline,
        caches: (PcmSource) -> WaveformPeakCache?,
        firstCol: Long,
        cols: Int,
        fpp: Double
    ): Pair<FloatArray, FloatArray> {
        val mins = FloatArray(cols)
        val maxs = FloatArray(cols)
        tl.fillWindow(firstCol, cols, fpp, caches, mins, maxs)
        return mins to maxs
    }

    @Test
    fun columnsHoldExactBucketAlignedMinMax() {
        // fpp = 320 = 5 whole buckets (5*64), so each column reads exactly 5 clean buckets:
        // column k ⇒ frames [k*320, k*320+320) ⇒ min = k*320, max = k*320+319.
        val total = 320 * 100
        val (tl, caches) = cacheOf(total)
        val fpp = 320.0
        val (mins, maxs) = window(tl, caches, firstCol = 0, cols = 100, fpp = fpp)

        for (k in 0 until 100) {
            assertEquals((k * 320).toFloat(), mins[k], "column $k min")
            assertEquals((k * 320 + 319).toFloat(), maxs[k], "column $k max")
        }
    }

    @Test
    fun sameAbsoluteColumnIsIdenticalAtEveryScrollOffset() {
        // THE anti-crawl proof. Absolute column K covers a fixed frame range; whether it appears at
        // window index 40 (firstCol=oldStart) or index 10 (firstCol=oldStart+30), its min/max must
        // be byte-for-byte identical. If this held for AudioScene, narration would not have jittered.
        val total = 320 * 200
        val (tl, caches) = cacheOf(total)
        val fpp = 317.0 // deliberately NOT a bucket multiple — the grid must still be deterministic
        val cols = 60

        // Capture the value of every absolute column from a set of overlapping window starts.
        val offsets = listOf(0L, 1L, 7L, 30L, 59L, 123L)
        val byAbsCol = HashMap<Long, Pair<Float, Float>>()
        for (start in offsets) {
            val (mins, maxs) = window(tl, caches, start, cols, fpp)
            for (i in 0 until cols) {
                val absCol = start + i
                val prior = byAbsCol[absCol]
                val now = mins[i] to maxs[i]
                if (prior != null) {
                    assertEquals(prior.first, now.first, "abs column $absCol min drifted with scroll")
                    assertEquals(prior.second, now.second, "abs column $absCol max drifted with scroll")
                } else {
                    byAbsCol[absCol] = now
                }
            }
        }
        // Sanity: the overlaps actually exercised shared columns.
        assertTrue(byAbsCol.size < offsets.size * cols, "offsets should overlap so columns are re-checked")
    }

    @Test
    fun subPixelScrollShiftsColumnsByExactlyOne() {
        // Advancing the window start by one column shifts the whole visible content left by exactly
        // one column (no partial re-bin): window(f+1)[i] == window(f)[i+1].
        val total = 320 * 120
        val (tl, caches) = cacheOf(total)
        val fpp = 250.0
        val cols = 50
        val (m0, x0) = window(tl, caches, firstCol = 10, cols = cols, fpp = fpp)
        val (m1, x1) = window(tl, caches, firstCol = 11, cols = cols, fpp = fpp)
        for (i in 0 until cols - 1) {
            assertEquals(m0[i + 1], m1[i], "min at shifted column $i")
            assertEquals(x0[i + 1], x1[i], "max at shifted column $i")
        }
    }

    @Test
    fun columnsBeforeZeroAndPastEofAreNaN() {
        val total = 320 * 10
        val (tl, caches) = cacheOf(total)
        val fpp = 320.0
        // Window starting 3 columns before frame 0.
        val (mins, maxs) = window(tl, caches, firstCol = -3, cols = 20, fpp = fpp)
        assertTrue(mins[0].isNaN() && maxs[0].isNaN(), "pre-zero column should be NaN")
        assertTrue(mins[1].isNaN(), "pre-zero column should be NaN")
        // Columns 3.. cover real frames.
        assertFalse(mins[3].isNaN(), "first real column should have data")
        // Past EOF (total = 10 columns of 320) → columns >= 13 are NaN.
        assertTrue(mins[19].isNaN(), "past-eof column should be NaN")
    }
}

/** In-memory PcmSource whose PCM sample value equals the absolute frame index (mono 16-bit). */
internal class FakeFrameValueSource(
    override val totalFrames: Int,
    override val id: String = "fake-frame-value",
    override val sampleRate: Int = 44100
) : PcmSource {
    override fun openReader(): AudioFileReader = FrameValueReader(totalFrames)
}

private class FrameValueReader(
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
        val frames = minOf(bytes.size / frameBytes, totalFrames - framePosition)
        var w = 0
        for (i in 0 until frames) {
            val v = (framePosition + i).toShort().toInt()
            bytes[w] = (v and 0xFF).toByte()
            bytes[w + 1] = ((v shr 8) and 0xFF).toByte()
            w += frameBytes
        }
        framePosition += frames
        return frames * frameBytes
    }
    override fun seek(frame: Long) { framePosition = frame.toInt().coerceIn(0, totalFrames) }
    override fun release() { opened = false }
}
