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
}