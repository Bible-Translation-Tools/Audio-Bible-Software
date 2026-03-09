package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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

    @Test
    fun testLocationUsesReaderWhenSinkPositionStaysZero() = runTest {
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

        assertTrue(player.getLocationInFrames() > 0, "Location should advance even if sink frame position is unavailable")
        player.release()
    }
}
