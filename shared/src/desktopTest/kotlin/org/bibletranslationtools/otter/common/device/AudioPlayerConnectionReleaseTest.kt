package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Leaving one screen for another must not take the audio device with it.
 *
 * The sink and the worker are app-scoped singletons shared by every screen, while an
 * [AudioPlayerConnection] belongs to one ViewModel. `release()` used to call `worker.release()`,
 * which CLOSES the hardware line — so a screen being torn down closed the output device out from
 * under its replacement. Compose Navigation constructs the incoming screen (and its `load()`, which
 * opens the line) before clearing the outgoing one, so the close landed after the open and nothing
 * reopened it: play then did nothing on that screen and on every screen after it, including on
 * returning to the first one, until the app was restarted.
 *
 * These run against the real [JvmAudioSink] deliberately. A test double whose open/close always
 * succeed cannot show this — the earlier connection-level tests used one and passed throughout.
 */
class AudioPlayerConnectionReleaseTest {

    private class CountingSink(private val inner: AudioSink) : AudioSink {
        var bytes = 0L
        override fun open(spec: AudioSpec) = inner.open(spec)
        override fun start() = inner.start()
        override fun write(data: ByteArray, offset: Int, size: Int): Int =
            inner.write(data, offset, size).also { bytes += it.coerceAtLeast(0) }
        override fun stop() = inner.stop()
        override fun drain() = inner.drain()
        override fun flush() = inner.flush()
        override fun close() = inner.close()
        override val isRunning: Boolean get() = inner.isRunning
        override val framePosition: Long get() = inner.framePosition
    }

    private class Fixture {
        val line = FakeSourceDataLine()
        val sink = CountingSink(JvmAudioSink(bufferMillis = 50) { line })
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val factory = AudioPlayerConnectionFactory.createForScope(sink, IdentityAudioProcessor(), scope)
        /** Both consume screens use the same fixed PLAYER_ID, which is what makes this subtle. */
        fun connection() = AudioPlayerConnection(90_001, factory, scope, Dispatchers.Default)
    }

    private suspend fun AudioPlayerConnection.loadAndPlayFully() {
        load(MockAudioFileReader(totalFrames = 20_000))
        delay(300)
        play()
        delay(900)
    }

    /**
     * The reported failure: the incoming screen loads first, the outgoing one is cleared second.
     */
    @Test
    fun theIncomingScreenStillPlaysWhenTheOutgoingOneIsClearedAfterIt() = runBlocking {
        with(Fixture()) {
            val a = connection()
            a.loadAndPlayFully()
            val afterA = sink.bytes
            assertTrue(afterA > 0, "the first screen should have played")

            val b = connection()
            b.load(MockAudioFileReader(totalFrames = 20_000))
            delay(300)
            a.release() // ...and only now is the outgoing ViewModel cleared.

            assertEquals(0, line.closes, "releasing one screen must not close the shared line")

            b.play()
            delay(900)
            assertTrue(sink.bytes > afterA, "the incoming screen should still reach the hardware")
            scope.cancel()
        }
    }

    /** The same, in the order that always happened to work — kept so a fix cannot regress it. */
    @Test
    fun theIncomingScreenStillPlaysWhenTheOutgoingOneIsClearedBeforeIt() = runBlocking {
        with(Fixture()) {
            val a = connection()
            a.loadAndPlayFully()
            val afterA = sink.bytes

            a.release()

            val b = connection()
            b.loadAndPlayFully()
            assertTrue(sink.bytes > afterA, "the incoming screen should still reach the hardware")
            scope.cancel()
        }
    }

    /**
     * The invariant, stated directly: nothing reachable from a screen may close the output line.
     * Only a device change or [AudioPlayerConnectionFactory.shutdown] may, and neither is a screen
     * going away. Asserted against the worker's own `release()` because that is what a screen used
     * to reach through, and the line must now survive it.
     */
    @Test
    fun nothingAScreenCanDoClosesTheLine() = runBlocking {
        with(Fixture()) {
            val a = connection()
            a.loadAndPlayFully()
            assertTrue(sink.bytes > 0, "precondition: audio reached the line")

            a.release()
            factory.getPlayerWorker().release()

            assertEquals(0, line.closes, "no screen-level teardown may close the shared line")
            assertTrue(line.isOpen, "the line has to still be open for the next screen")

            // And the next screen really can still play through it.
            val before = sink.bytes
            connection().loadAndPlayFully()
            assertTrue(sink.bytes > before, "the next screen must still reach the hardware")
            scope.cancel()
        }
    }

    /** The audio system may still release it — that is the whole point of keeping the capability. */
    @Test
    fun theAudioSystemItselfStillReleasesTheLine() = runBlocking {
        with(Fixture()) {
            connection().loadAndPlayFully()
            factory.shutdown()
            assertEquals(1, line.closes, "shutdown is the audio system's own call and must close it")
            scope.cancel()
        }
    }

    /**
     * A screen giving its audio back must not leave the worker holding a closed reader.
     *
     * `load()` hands the reader to the worker, and the worker keeps using it: `connect()` pauses
     * before every load, and pausing seeks the loaded reader. A connection that closed its own
     * reader on the way out therefore made the NEXT connect() throw
     * "Tried to seek before opening file" — aborting the transport for every connection from then
     * on, narration included, until the app was restarted.
     *
     * Narration's id is used deliberately: the failure was app-wide, not per-screen.
     */
    @Test
    fun anotherConnectionStillPlaysAfterTheFirstGivesItsAudioBack() = runBlocking {
        with(Fixture()) {
            val consume = connection()
            consume.loadAndPlayFully()
            val afterConsume = sink.bytes
            assertTrue(afterConsume > 0, "precondition: the first screen played")

            consume.release()

            val narration = AudioPlayerConnection(90_100, factory, scope, Dispatchers.Default)
            narration.loadAndPlayFully()

            assertTrue(
                sink.bytes > afterConsume,
                "a different connection must still reach the hardware after the first released"
            )
            scope.cancel()
        }
    }
}
