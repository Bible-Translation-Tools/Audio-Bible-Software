package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What happens when the user changes output device mid-playback.
 *
 * The hazard is specific and it is not "the audio hiccups". A sink handed to the worker is a handle to
 * a device that has **not been opened**: the format is the take's, not the device's, so only a `load()`
 * can open it. Anything that plays before that re-open is writing into a line that does not exist —
 * `write()` returns 0 forever, and the playback loop rewinds the reader and spins.
 *
 * That is what the old implementation did, by calling `player.play()` directly whenever the outgoing
 * sink had been running. It also left the factory believing the departing connection still held the
 * hardware, which mattered more once resuming stopped going through `connect()`: the connection would
 * then have skipped the re-open on its next play as well.
 */
class AudioDeviceChangeTest {

    /**
     * The swap is done while PAUSED, which is both the common case (pause, pick a device, press play)
     * and the only one that exposes the defect. Swapping mid-playback happens to be survivable for the
     * wrong reason: a session is still producing, so the stray `player.play()` the old implementation
     * made was rejected as redundant and nothing was written anywhere. Paused, there is no session to
     * make it redundant — and since the line is deliberately left running across a pause now (stopping
     * it costs a 230-310ms restart), the "was the sink running?" test it used to gate on answers yes.
     */
    @Test
    fun changingDeviceWhilePausedDoesNotPlayIntoTheUnopenedSink() = runTest {
        withDeviceSwap { old, new, connection ->
            connection.play()
            awaitWrites(old, atLeast = 5)
            connection.pause()
            delay(50)

            factory.updateHardwareSink(new)
            // Real time, not runTest's virtual clock: the writer is a coroutine on a real dispatcher, so
            // a skipped `delay` gives it no chance to misbehave and the assertion below would pass
            // without ever having been tested.
            elapseRealMillis(200)

            assertEquals(
                0,
                new.writes.value,
                "audio was written to a device that was never opened. On real hardware that write " +
                    "returns 0 and the loop rewinds the reader and retries forever, which is a hung " +
                    "playback thread, not a glitch."
            )
            assertFalse("open" in new.calls.value, "nothing should have opened the new device yet")
        }
    }

    @Test
    fun theNextPlayOpensTheNewDeviceAndPlaysThroughIt() = runTest {
        withDeviceSwap { old, new, connection ->
            connection.play()
            awaitWrites(old, atLeast = 5)
            val writesOnOldAtSwap = old.writes.value

            factory.updateHardwareSink(new)
            delay(50)

            connection.play()
            awaitWrites(new, atLeast = 5)

            assertTrue(
                "open" in new.calls.value,
                "the next play must re-open the new device — that is the only thing that knows the " +
                    "take's format. Calls were ${new.calls.value}"
            )
            assertTrue(
                old.writes.value <= writesOnOldAtSwap + 1,
                "the old device kept receiving audio after it was replaced " +
                    "(${old.writes.value} vs ${writesOnOldAtSwap} at the swap)"
            )
        }
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────

    private class Fixture(
        val factory: AudioPlayerConnectionFactory,
        val scope: CoroutineScope
    )

    private suspend fun withDeviceSwap(
        body: suspend Fixture.(old: PacedAudioSink, new: PacedAudioSink, AudioPlayerConnection) -> Unit
    ) {
        val old = PacedAudioSink()
        val new = PacedAudioSink()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val factory = AudioPlayerConnectionFactory.createForScope(old, DefaultAudioProcessor(), scope)
        val connection = AudioPlayerConnection(1, factory, scope, Dispatchers.Default)
        try {
            connection.load(MockAudioFileReader(totalFrames = 1024 * 400))
            delay(50)
            Fixture(factory, scope).body(old, new, connection)
        } finally {
            scope.cancel()
        }
    }

    /** Burns [millis] of WALL time. `runTest` fast-forwards `delay`, and the writer does not. */
    private suspend fun elapseRealMillis(millis: Long) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < deadline) delay(1)
    }

    private suspend fun awaitWrites(sink: PacedAudioSink, atLeast: Int) {
        val deadline = System.nanoTime() + AWAIT_MILLIS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (sink.writes.value >= atLeast) return
            delay(2)
        }
        throw AssertionError("the sink never reached $atLeast writes (saw ${sink.writes.value})")
    }

    private companion object {
        const val AWAIT_MILLIS = 5_000L
    }
}
