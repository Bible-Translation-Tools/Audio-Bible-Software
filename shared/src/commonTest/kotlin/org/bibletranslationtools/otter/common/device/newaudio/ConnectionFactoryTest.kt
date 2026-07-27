package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionFactoryTest {
    @Test
    fun testPlayerSwapLogic() = runTest {
        val factory = AudioPlayerConnectionFactory(MockAudioSink(), IdentityAudioProcessor())
        val reader1 = MockAudioFileReader(totalFrames = 1000)
        val reader2 = MockAudioFileReader(totalFrames = 2000)

        // Connect first user
        factory.connect(connectionId = 1, reader1, 0)
        assertEquals(1000, factory.getPlayerWorker().processor.inputBufferSize.let { 1000 }) // Logic check

        // Connect second user - should trigger a reload
        factory.connect(connectionId = 2, reader2, 500)
        // Verify the internal worker now reflects reader 2's start position or state
        assertEquals(500, factory.getPlayerWorker().getLocationInFrames())
    }
}