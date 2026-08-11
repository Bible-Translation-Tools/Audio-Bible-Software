package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reading the playback position must never block the caller, because the caller is the UI thread and
 * it reads once per display frame.
 *
 * Every display clock in both apps is built as
 * `PlaybackDisplayPosition(positionSource = { audioPlayer.getLocationInFrames().toLong() }, …)` and
 * its `onFrame` runs on the main thread from `withFrameNanos`. So a position read that
 * waits on the audio worker's mutex stalls the whole display — the playhead freezes, the waveform stops
 * scrolling, the elapsed time stops counting — while the audio, already queued in the hardware buffer,
 * carries on perfectly. A stall with no audible glitch is the signature of this, as opposed to an
 * underrun, which you would hear.
 *
 * The same realisation is already recorded on [AudioBufferPlayer.isPositionReliable], which exists as a
 * lock-free variant precisely because "taking the mutex at 120 Hz is not" harmless — but
 * [AudioPlayerConnection.getLocationInFrames] kept reading the locking [AudioBufferPlayer.isSinkRunning]
 * next to it.
 *
 * Holding that mutex is not hypothetical or brief: `load()` holds it across `reader.open()` and
 * `sink.open(spec)`, and opening real audio hardware takes tens of milliseconds. `connect()` calls
 * `load()` on every resume and every take switch, which is exactly when someone is watching the
 * playhead.
 */
class AudioPlayerPositionReadTest {

    @Test
    fun readingThePositionDoesNotWaitForTheWorkersMutex() = runTest {
        withTransport(takeFrames = AudioTransportHarness.BUFFER_FRAMES * 400) {
            val connection = connection()
            loadAndPlay(connection)
            // Playing and active, so the read takes its live-position path rather than the early-out.
            awaitWrites(5)

            // Occupy the worker's mutex the way a real load does: held across a slow hardware open.
            sink.holdOpen()
            transportScope.launch { worker.load(newTake()) }
            awaitCondition("load() to be inside the hardware open, holding the mutex") {
                sink.openEntered.value
            }
            transportScope.launch {
                delay(HOLD_MILLIS)
                sink.releaseOpen()
            }

            val startedAt = System.nanoTime()
            repeat(READS) { connection.getLocationInFrames() }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            // Generous: $READS reads have a whole display frame's budget between them, and the failure
            // this guards against is a block for the entire ${HOLD_MILLIS}ms hold.
            assertTrue(
                elapsedMillis < BUDGET_MILLIS,
                "$READS position reads took ${elapsedMillis}ms while the worker's mutex was held; " +
                    "the UI thread does this every display frame"
            )

            sink.releaseOpen()
        }
    }

    private companion object {
        const val READS = 10
        const val HOLD_MILLIS = 400L
        const val BUDGET_MILLIS = 100L
    }
}
