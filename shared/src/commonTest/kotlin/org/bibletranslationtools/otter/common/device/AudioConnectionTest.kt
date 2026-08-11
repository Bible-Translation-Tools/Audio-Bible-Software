package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.fail

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
        // The recorder's read loop stays on a real dispatcher: it runs until cancelled, so putting it
        // on the test scheduler would make advanceUntilIdle() spin forever trying to drain it.
        val factory = AudioRecorderConnectionFactory(source)

        val rec1 = AudioRecorderConnection(id = 1, factory = factory, scope = this)
        val rec2 = AudioRecorderConnection(id = 2, factory = factory, scope = this)

        rec1.start(AudioSpec())
        advanceUntilIdle()
        assertTrue(source.isStarted)

        rec2.start(AudioSpec()) // Should evict rec1

        // The handover crosses the recorder's real dispatcher (stop() now joins the read loop before
        // releasing the source), so it is awaited in real time against the actual condition. The spin
        // this replaces called runCurrent() twenty times and then asserted — no wait at all for the
        // real-thread part, and its `return@repeat` was a continue rather than a break, so the
        // "switched" flag it checked was only ever set by luck of timing.
        awaitReal("recorder 2 to own the hardware") { factory.isActiveRecorder(2) }

        // Logic check: Factory should have stopped rec1's worker before starting rec2
        assertTrue(factory.isActiveRecorder(2))
        assertFalse(factory.isActiveRecorder(1))
    }

    /**
     * Polls [predicate] in REAL time, because the thing being awaited genuinely happens on another
     * dispatcher. Unlike a fixed number of scheduler pumps this cannot pass or fail by luck: it
     * either observes the condition or reports what it was waiting for.
     */
    private suspend fun awaitReal(
        what: String,
        timeoutMs: Long = 5_000,
        predicate: () -> Boolean
    ) = withContext(Dispatchers.Default) {
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000
        while (!predicate()) {
            if (System.nanoTime() > deadlineNanos) fail("timed out after ${timeoutMs}ms waiting for $what")
            delay(5)
        }
    }
}
