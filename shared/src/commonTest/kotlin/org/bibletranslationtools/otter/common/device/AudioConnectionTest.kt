package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class AudioConnectionTest {

    @Test
    fun testPlayerConnectionSwapPersistence() = runTest {
        val sink = MockAudioSink()
        val processor = IdentityAudioProcessor()
        val factory = AudioPlayerConnectionFactory.createForScope(
            sink = sink,
            processor = processor,
            scope = backgroundScope
        )

        // Use backgroundScope so players are cleaned up automatically
        val conn1 = AudioPlayerConnection(id = 1, factory = factory, scope = backgroundScope)
        val conn2 = AudioPlayerConnection(id = 2, factory = factory, scope = backgroundScope)

        // Large files so they don't finish instantly
        val reader1 = MockAudioFileReader(totalFrames = 1000000)
        val reader2 = MockAudioFileReader(totalFrames = 1000000)

        // 1. Load and Play Connection 1
        conn1.load(reader1)
        conn1.play()

        // Progress pending coroutines for this turn.
        runCurrent()
        assertTrue(factory.isActiveConnection(1), "Conn1 should hold the hardware connection")

        // 2. Connection 2 takes over
        conn2.load(reader2)
        conn2.play()

        runCurrent()

        assertFalse(factory.isActiveConnection(1), "Conn1 should be evicted from hardware")
        assertTrue(factory.isActiveConnection(2), "Conn2 should now own the hardware")

        // 3. Connection 1 resumes
        conn1.play()
        runCurrent()

        assertTrue(factory.isActiveConnection(1), "Conn1 should reclaim the hardware connection")
    }

    @Test
    fun testRecorderExclusiveAccess() = runTest {
        val source = MockAudioSource()
        val factory = AudioRecorderConnectionFactory(source)

        val rec1 = AudioRecorderConnection(id = 1, factory = factory, scope = this)
        val rec2 = AudioRecorderConnection(id = 2, factory = factory, scope = this)

        rec1.start(AudioSpec())
        advanceUntilIdle()
        assertTrue(source.isStarted)

        rec2.start(AudioSpec()) // Should evict rec1
        var switched = false
        repeat(20) {
            runCurrent()
            if (factory.isActiveRecorder(2)) {
                switched = true
                return@repeat
            }
        }

        // Logic check: Factory should have stopped rec1's worker before starting rec2
        assertTrue(switched)
        assertTrue(factory.isActiveRecorder(2))
        assertFalse(factory.isActiveRecorder(1))
    }
}
