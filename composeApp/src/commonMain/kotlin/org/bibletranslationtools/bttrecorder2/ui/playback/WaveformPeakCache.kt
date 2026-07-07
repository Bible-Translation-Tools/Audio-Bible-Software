package org.bibletranslationtools.bttrecorder2.ui.playback

import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-memory min/max peaks for ONE [PcmSource] at [BUCKET_FRAMES] frames per bucket, in
 * SOURCE-absolute frames. This is what makes live per-frame waveform rendering possible:
 * the draw loop reads these arrays instead of touching disk. Immutable after build;
 * edits never modify it (they remap through [AudioTimeline] on top).
 */
class WaveformPeakCache(val totalFrames: Int) {
    companion object {
        const val BUCKET_FRAMES = 64
    }

    val bucketCount: Int = (totalFrames + BUCKET_FRAMES - 1) / BUCKET_FRAMES

    // Interleaved [min0, max0, min1, max1, ...]. Written only by the builder, strictly
    // below the published builtBuckets index — the snapshot-state write is the safe
    // publication point for draw-thread reads.
    private val peaks = FloatArray(bucketCount * 2)

    /**
     * Progressive fill: buckets < builtBuckets are valid. Snapshot state so a PAUSED
     * screen still redraws as the build progresses (during playback the position updates
     * already invalidate every frame). WRITTEN ONLY ON MAIN, throttled by the builder.
     * Read this in onDrawBehind (never in a drawWithCache cache scope).
     */
    val builtBuckets = mutableIntStateOf(0)

    fun min(bucket: Int): Float = peaks[bucket * 2]
    fun max(bucket: Int): Float = peaks[bucket * 2 + 1]

    internal fun write(bucket: Int, mn: Float, mx: Float) {
        peaks[bucket * 2] = mn
        peaks[bucket * 2 + 1] = mx
    }
}

/**
 * Streams [source] sequentially and fills [cache]. Call on Dispatchers.IO; cancellable
 * between reads (take switches cancel stale builds). Publishes progress on
 * [publishContext] (the main dispatcher in production — snapshot-state writes must
 * happen there; injectable so tests can run without a Main dispatcher) every
 * [PUBLISH_EVERY_BUCKETS] buckets and once at completion.
 */
suspend fun buildPeakCache(
    source: PcmSource,
    cache: WaveformPeakCache,
    publishContext: kotlin.coroutines.CoroutineContext = Dispatchers.Main.immediate
) {
    val reader = source.openReader()
    try {
        reader.open()
        val totalFrames = cache.totalFrames
        if (totalFrames <= 0) return

        val channels = reader.spec.channels.coerceAtLeast(1)
        val bytesPerFrame = reader.spec.bytesPerFrame.coerceAtLeast(2)
        val buf = ByteArray(65536)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

        reader.seek(0)
        var frameIndex = 0
        var bucket = 0
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        var framesInBucket = 0
        var lastPublished = 0
        var retry = 0

        while (frameIndex < totalFrames && reader.hasRemaining()) {
            if (!currentCoroutineContext().isActive) return
            val bytesRead = reader.getPcmBuffer(buf)
            if (bytesRead <= 0) {
                if (++retry >= 10) break else continue
            }
            retry = 0
            bb.position(0)
            val framesRead = bytesRead / bytesPerFrame
            for (f in 0 until framesRead) {
                // Channel 0 is representative for the overview (matches the previous
                // minimap renderer's behavior).
                val sample = bb.short.toFloat()
                for (c in 1 until channels) bb.short
                if (sample < mn) mn = sample
                if (sample > mx) mx = sample
                frameIndex++
                if (++framesInBucket == WaveformPeakCache.BUCKET_FRAMES) {
                    cache.write(bucket, mn, mx)
                    bucket++
                    framesInBucket = 0
                    mn = Float.MAX_VALUE
                    mx = -Float.MAX_VALUE
                }
                if (frameIndex >= totalFrames) break
            }
            if (bucket - lastPublished >= PUBLISH_EVERY_BUCKETS) {
                lastPublished = bucket
                val publish = bucket
                withContext(publishContext) {
                    cache.builtBuckets.intValue = publish
                }
            }
        }

        // Flush the final partial bucket.
        if (framesInBucket > 0 && bucket < cache.bucketCount) {
            cache.write(bucket, mn, mx)
            bucket++
        }
        val publish = bucket
        withContext(publishContext) {
            cache.builtBuckets.intValue = publish
        }
    } finally {
        reader.release()
    }
}

private const val PUBLISH_EVERY_BUCKETS = 4096

/**
 * Aggregates timeline frames [fromF, toF) into one (min, max) pair written into
 * out[0]/out[1], reading only buckets below each cache's builtBuckets. Returns false
 * when the range has no built data (caller draws nothing — progressive fill).
 *
 * Walks the (usually one) segment(s) overlapping the range; a pixel column at the
 * 10-second zoom touches ~4–6 base buckets, so this is a handful of array reads.
 */
/**
 * Fills one visible window of waveform columns in a single pass. This is the per-frame
 * hot path, so it is strictly allocation-free and hoists the expensive per-column work
 * of [aggregate] (boxed mapToSource pairs, cache map lookups, snapshot-state reads of
 * builtBuckets) to once per SEGMENT: at 1600 columns × 120 fps those per-column costs
 * were ~380k allocations/sec — the source of periodic GC hiccups.
 *
 * Column k (absolute grid) covers timeline frames [floor(k*fpp), floor((k+1)*fpp)) —
 * deterministic per k, so column contents never re-bin as the window scrolls. The grid
 * math uses Double: at Float precision, k*fpp loses frame accuracy past ~2^24 frames
 * (~6 minutes of audio).
 *
 * mins/maxs[i] receive column (firstCol + i); Float.NaN marks columns with no built
 * data (unbuilt cache region, before frame 0, past EOF).
 */
fun AudioTimeline.fillWindow(
    firstCol: Long,
    columnCount: Int,
    fpp: Double,
    caches: (PcmSource) -> WaveformPeakCache?,
    mins: FloatArray,
    maxs: FloatArray
) {
    java.util.Arrays.fill(mins, 0, columnCount, Float.NaN)
    java.util.Arrays.fill(maxs, 0, columnCount, Float.NaN)
    if (totalFrames <= 0 || columnCount <= 0) return

    val windowFromF = kotlin.math.floor(firstCol * fpp).toInt()
    val windowToF = kotlin.math.floor((firstCol + columnCount) * fpp).toInt()

    for (s in segments.indices) {
        val seg = segments[s]
        val segStart = segmentStartFrame(s)
        val segEnd = segStart + (seg.sourceFrames.last - seg.sourceFrames.first + 1)
        if (segEnd <= windowFromF || segStart >= windowToF) continue

        // Hoisted per segment (NOT per column): cache lookup + snapshot read.
        val cache = caches(seg.source) ?: continue
        val built = cache.builtBuckets.intValue
        if (built <= 0) continue

        // Columns this segment can contribute to.
        val kLo = maxOf(firstCol, kotlin.math.floor(segStart / fpp).toLong())
        val kHi = minOf(firstCol + columnCount - 1, kotlin.math.floor((segEnd - 1) / fpp).toLong())
        var k = kLo
        while (k <= kHi) {
            val colFromF = maxOf(kotlin.math.floor(k * fpp).toInt(), segStart)
            val colToF = minOf(kotlin.math.floor((k + 1) * fpp).toInt(), segEnd)
            if (colToF > colFromF) {
                val srcFrom = seg.sourceFrames.first + (colFromF - segStart)
                val srcTo = srcFrom + (colToF - colFromF) - 1
                var b = srcFrom / WaveformPeakCache.BUCKET_FRAMES
                val bEnd = srcTo / WaveformPeakCache.BUCKET_FRAMES
                val i = (k - firstCol).toInt()
                while (b <= bEnd) {
                    if (b < built) {
                        val bMin = cache.min(b)
                        val bMax = cache.max(b)
                        if (mins[i].isNaN() || bMin < mins[i]) mins[i] = bMin
                        if (maxs[i].isNaN() || bMax > maxs[i]) maxs[i] = bMax
                    }
                    b++
                }
            }
            k++
        }
    }
}

fun AudioTimeline.aggregate(
    fromF: Int,
    toF: Int,
    caches: (PcmSource) -> WaveformPeakCache?,
    out: FloatArray
): Boolean {
    if (toF <= fromF || totalFrames <= 0) return false
    var mn = Float.MAX_VALUE
    var mx = -Float.MAX_VALUE
    var found = false

    var frame = fromF.coerceAtLeast(0)
    val end = toF.coerceAtMost(totalFrames)
    while (frame < end) {
        val (segIdx, srcFrame) = mapToSource(frame)
        val seg = segments[segIdx]
        // Frames remaining in this segment from srcFrame, limited by the request.
        val segRemaining = seg.sourceFrames.last - srcFrame + 1
        val take = minOf(segRemaining, end - frame)
        val cache = caches(seg.source)
        if (cache != null) {
            val built = cache.builtBuckets.intValue
            var b = srcFrame / WaveformPeakCache.BUCKET_FRAMES
            val bEnd = (srcFrame + take - 1) / WaveformPeakCache.BUCKET_FRAMES
            while (b <= bEnd) {
                if (b < built) {
                    val bMin = cache.min(b)
                    val bMax = cache.max(b)
                    if (bMin < mn) mn = bMin
                    if (bMax > mx) mx = bMax
                    found = true
                }
                b++
            }
        }
        frame += take
    }

    if (!found) return false
    out[0] = mn
    out[1] = mx
    return true
}
