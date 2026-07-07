package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioBufferPlayerTest {

    @Test
    fun testPlaybackLifecycle() = runTest {
        val sink = MockAudioSink()
        val processor = IdentityAudioProcessor()
        val player = AudioBufferPlayer(sink, processor, this)

        // Assuming a MockReader that provides 1024 bytes
        val mockReader = MockAudioFileReader(totalFrames = 512)

        player.load(mockReader)
        assertTrue(sink.isOpen, "Sink should be open after load")

        player.play()
        // Allow the coroutine to churn
        testScheduler.advanceUntilIdle()

        assertTrue(sink.bytesWritten > 0, "Sink should have received data")
        player.release()
    }

    // Sink never reports progress (framePosition stuck at 0) even though it claims to be
    // running. Per the current contract, getLocationInFrames() trusts the sink while it is
    // running (sessionStartFrame + sink.framePosition, capped by the write cursor), so a
    // stuck-at-zero sink correctly pins the reported location at 0 -- callers are expected
    // to notice this via isPositionReliable rather than getLocationInFrames() silently
    // substituting the reader's position.
    @Test
    fun testLocationStaysAtSinkPositionWhenSinkPositionStaysZero() = runTest {
        val sink = object : AudioSink {
            override val isRunning: Boolean
                get() = true
            override val framePosition: Long
                get() = 0L

            override fun open(spec: AudioSpec) = Unit
            override fun start() = Unit
            override fun write(data: ByteArray, offset: Int, size: Int): Int = size
            override fun stop() = Unit
            override fun drain() = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }

        val processor = IdentityAudioProcessor()
        val player = AudioBufferPlayer(sink, processor, this)
        val reader = MockAudioFileReader(totalFrames = 4_096)

        player.load(reader)
        player.play()
        testScheduler.advanceUntilIdle()

        assertEquals(0L, player.getLocationInFrames(), "While running, location should track the sink's own frame position")
        player.release()
    }

    // Sink reports a fixed, stale position while running. Same contract as above: the
    // running sink's framePosition is authoritative, so the reported location tracks it
    // (capped by the write cursor) rather than falling back to the reader's position.
    @Test
    fun testLocationTracksSinkPositionWhenSinkPositionIsStale() = runTest {
        val sink = object : AudioSink {
            override val isRunning: Boolean
                get() = true
            override val framePosition: Long
                get() = 1L

            override fun open(spec: AudioSpec) = Unit
            override fun start() = Unit
            override fun write(data: ByteArray, offset: Int, size: Int): Int = size
            override fun stop() = Unit
            override fun drain() = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }

        val processor = IdentityAudioProcessor()
        val player = AudioBufferPlayer(sink, processor, this)
        val reader = MockAudioFileReader(totalFrames = 4_096)

        player.load(reader)
        player.play()
        testScheduler.advanceUntilIdle()

        assertEquals(1L, player.getLocationInFrames(), "While running, location should track the sink's own (stale) frame position")
        player.release()
    }

    // While the sink is not running, getLocationInFrames() falls back to the write cursor
    // (lastKnownLocationInFrames), which IS driven by the reader as data is written -- and
    // isPositionReliable surfaces that this fallback is in effect.
    @Test
    fun testLocationUsesWriteCursorWhenSinkNotRunning() = runTest {
        val sink = object : AudioSink {
            override val isRunning: Boolean
                get() = false
            override val framePosition: Long
                get() = 0L

            override fun open(spec: AudioSpec) = Unit
            override fun start() = Unit
            override fun write(data: ByteArray, offset: Int, size: Int): Int = size
            override fun stop() = Unit
            override fun drain() = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }

        val processor = IdentityAudioProcessor()
        val player = AudioBufferPlayer(sink, processor, this)
        val reader = MockAudioFileReader(totalFrames = 4_096)

        player.load(reader)
        player.play()
        testScheduler.advanceUntilIdle()

        assertFalse(player.isPositionReliable, "isPositionReliable should be false while the sink is not running")
        assertTrue(player.getLocationInFrames() > 0, "Location should advance via the write cursor even though the sink never reports running")
        player.release()
    }
}
