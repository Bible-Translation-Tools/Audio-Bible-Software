package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AudioConnectionTest {

    @Test
    fun testPlayerConnectionSwapPersistence() = runTest {
        val sink = MockAudioSink()
        val processor = IdentityAudioProcessor()
        val factory = AudioPlayerConnectionFactory(sink, processor)

        // Use backgroundScope so players are cleaned up automatically
        val conn1 = AudioPlayerConnection(id = 1, factory = factory, scope = backgroundScope)
        val conn2 = AudioPlayerConnection(id = 2, factory = factory, scope = backgroundScope)

        // Large files so they don't finish instantly
        val reader1 = MockAudioFileReader(totalFrames = 1000000)
        val reader2 = MockAudioFileReader(totalFrames = 1000000)

        // 1. Load and Play Connection 1
        conn1.load(reader1)
        conn1.play()

        // runCurrent() starts the coroutine but stops before it finishes the whole loop
        runCurrent()

        assertTrue(conn1.isPlaying(), "Conn1 should be playing")

        // 2. Connection 2 takes over
        conn2.load(reader2)
        conn2.play()

        runCurrent()

        assertFalse(conn1.isPlaying(), "Conn1 should be evicted from hardware")
        assertTrue(conn2.isPlaying(), "Conn2 should now own the hardware")

        // 3. Connection 1 resumes
        conn1.play()
        runCurrent()

        assertTrue(conn1.isPlaying(), "Conn1 should be playing again")
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
        advanceUntilIdle()

        // Logic check: Factory should have stopped rec1's worker before starting rec2
        assertTrue(factory.isActiveRecorder(2))
        assertFalse(factory.isActiveRecorder(1))
    }
}