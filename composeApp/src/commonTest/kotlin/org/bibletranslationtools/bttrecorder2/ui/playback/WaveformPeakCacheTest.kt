package org.bibletranslationtools.bttrecorder2.ui.playback

import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fake source emits sample value == frame index, so bucket b of the cache must
 * hold min = b*64 and max = min(b*64+63, total-1) exactly — any binning drift fails.
 */
class WaveformPeakCacheTest {

    private fun builtCache(total: Int): Pair<AudioTimeline, WaveformPeakCache> {
        val source = FakePcmSourceTL(total)
        val cache = WaveformPeakCache(source.totalFrames)
        runBlocking { buildPeakCache(source, cache, EmptyCoroutineContext) }
        return AudioTimeline.ofWholeSource(source) to cache
    }

    @Test
    fun buildBinsExactly() {
        val (_, cache) = builtCache(1000)
        assertEquals(16, cache.bucketCount)              // ceil(1000/64)
        assertEquals(16, cache.builtBuckets.intValue)
        assertEquals(0f, cache.min(0))
        assertEquals(63f, cache.max(0))
        assertEquals(64f, cache.min(1))
        assertEquals(127f, cache.max(1))
        // Final partial bucket: frames 960..999.
        assertEquals(960f, cache.min(15))
        assertEquals(999f, cache.max(15))
    }

    @Test
    fun aggregateWholeBucketsAndPartials() {
        val (tl, cache) = builtCache(1000)
        val caches = { _: PcmSource -> cache }
        val out = FloatArray(2)

        assertTrue(tl.aggregate(0, 128, caches, out))
        assertEquals(0f, out[0]); assertEquals(127f, out[1])

        assertTrue(tl.aggregate(64, 128, caches, out))
        assertEquals(64f, out[0]); assertEquals(127f, out[1])

        // A sub-bucket range reads at bucket granularity (whole bucket 0).
        assertTrue(tl.aggregate(0, 10, caches, out))
        assertEquals(0f, out[0]); assertEquals(63f, out[1])
    }

    @Test
    fun aggregateSpansCutBoundary() {
        val (whole, cache) = builtCache(1000)
        val caches = { _: PcmSource -> cache }
        val tl = whole.cut(100, 900)   // keeps source [0,100) + [900,1000)
        assertEquals(200, tl.totalFrames)

        val out = FloatArray(2)
        // Edited frames 90..109 = source 90..99 (bucket 1) + source 900..909 (bucket 14:
        // source 896..959). Bucket-granularity min/max: min from bucket 1 = 64,
        // max from bucket 14 = 959.
        assertTrue(tl.aggregate(90, 110, caches, out))
        assertEquals(64f, out[0])
        assertEquals(959f, out[1])
    }

    @Test
    fun aggregateRespectsBuildProgress() {
        val source = FakePcmSourceTL(1000)
        val unbuilt = WaveformPeakCache(source.totalFrames)   // builtBuckets stays 0
        val tl = AudioTimeline.ofWholeSource(source)
        val out = FloatArray(2)
        assertFalse(tl.aggregate(0, 128, { unbuilt }, out))
    }

    @Test
    fun aggregateEmptyOrOutOfRange() {
        val (tl, cache) = builtCache(1000)
        val caches = { _: PcmSource -> cache }
        val out = FloatArray(2)
        assertFalse(tl.aggregate(50, 50, caches, out))
        assertFalse(tl.aggregate(-10, 0, caches, out))
    }
}
