package org.bibletranslationtools.orature.ui.workbook

import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a whole audio file's min/max peaks ONCE (one pass at load) into an in-memory array, so the
 * scrolling waveform can be drawn each display frame by slicing a window — with no per-tick disk read
 * or codec decode. This is what keeps the source-audio waveform (Consume/Chunking) smooth: reading
 * ~10s of a compressed source file every tick costs ~25ms (≈17fps + laggy scrub); a slice costs µs.
 *
 * One min/max pair is stored per "bucket" of [bucketFrames] frames, where a bucket is one on-screen
 * pixel column at the standard zoom (framesOnScreen / width). [window] returns width*2 min/max values
 * for the columns starting at a given left-edge frame, zero-padding before 0 / past the end.
 */
class PrecomputedWaveform private constructor(
    private val bucketFrames: Int,
    private val numBuckets: Int,
    private val peaks: FloatArray
) {
    /** Fill [out] (size width*2) with the min/max columns whose left edge is [leftFrame]. */
    fun window(leftFrame: Int, out: FloatArray) {
        val cols = out.size / 2
        val leftBucket = Math.floorDiv(leftFrame, bucketFrames)
        for (i in 0 until cols) {
            val b = leftBucket + i
            if (b in 0 until numBuckets) {
                out[i * 2] = peaks[b * 2]
                out[i * 2 + 1] = peaks[b * 2 + 1]
            } else {
                out[i * 2] = 0f
                out[i * 2 + 1] = 0f
            }
        }
    }

    companion object {
        private const val READ_CHUNK_BYTES = 1 shl 16

        /** Decode [reader] fully and build the peak cache. Call off the main thread (one full read). */
        fun build(reader: AudioFileReader, width: Int, secondsOnScreen: Int, sampleRate: Int): PrecomputedWaveform {
            val bucketFrames = (secondsOnScreen * sampleRate / width).coerceAtLeast(1)
            val total = reader.totalFrames.coerceAtLeast(0)
            val numBuckets = (total + bucketFrames - 1) / bucketFrames
            val peaks = FloatArray((numBuckets * 2).coerceAtLeast(2))

            val frameSize = reader.spec.bytesPerFrame.coerceAtLeast(2)
            val shortsPerFrame = (frameSize / 2).coerceAtLeast(1)
            val buf = ByteArray(READ_CHUNK_BYTES)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

            reader.seek(0)
            var bucket = 0
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            var countInBucket = 0
            loop@ while (reader.hasRemaining() && bucket < numBuckets) {
                val bytes = reader.getPcmBuffer(buf)
                if (bytes <= 0) break
                bb.position(0)
                val frames = bytes / frameSize
                for (f in 0 until frames) {
                    // Use the first channel; skip any others.
                    val sample = bb.short.toFloat()
                    for (c in 1 until shortsPerFrame) bb.short
                    if (sample < min) min = sample
                    if (sample > max) max = sample
                    countInBucket++
                    if (countInBucket >= bucketFrames) {
                        peaks[bucket * 2] = min
                        peaks[bucket * 2 + 1] = max
                        bucket++
                        min = Float.MAX_VALUE; max = -Float.MAX_VALUE; countInBucket = 0
                        if (bucket >= numBuckets) break@loop
                    }
                }
            }
            if (countInBucket > 0 && bucket < numBuckets) {
                peaks[bucket * 2] = min
                peaks[bucket * 2 + 1] = max
            }
            return PrecomputedWaveform(bucketFrames, numBuckets, peaks)
        }
    }
}
