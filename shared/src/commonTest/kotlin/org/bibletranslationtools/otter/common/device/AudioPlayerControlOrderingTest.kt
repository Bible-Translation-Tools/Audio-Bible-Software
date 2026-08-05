package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transport calls must take effect in the order they were made.
 *
 * Each one is dispatched independently — `scope.launch(Dispatchers.Default) { … }` per call — so nothing
 * orders them and nothing stops two from running at once. The factory's mutex serialises `connect()`
 * alone, which is not the same thing: `pause()` reads the position and *then* pauses the worker, while
 * `play()` connects and *then* plays it, and those two pairs can interleave in any order.
 *
 * The consequence is not subtle. Toggle quickly and a stale `pause()` lands after a later `play()`,
 * killing playback the user just asked for; do it repeatedly and playback never gets going at all. From
 * the field, eight seconds of toggling with the display advancing the whole time:
 *
 * ```
 * display=13740    source=0   drift=n/a   unreliable/s=19
 * display=158581   source=0   drift=n/a   unreliable/s=24
 * display=232770   source=52888                              <- audio finally starts
 * display=97030    source=97032   drift=-6..179884            <- display sheds 4 seconds
 * ```
 *
 * `source=0` and `drift=n/a` together mean the sink was never started: no audio played. The reporter's
 * description matches exactly — "it doesn't even start playing until I stop toggling, and yet the
 * visualization has moved a lot, then it jumps way back". The display is a symptom here; the transport
 * ignoring its instructions is the bug.
 */
class AudioPlayerControlOrderingTest {

    /**
     * The take is long enough that it cannot end on its own, so "audio is flowing" is unambiguous.
     * Nothing is awaited between the toggles — that is the point, and it is what a double-tapping user
     * produces.
     */
    @Test
    fun spammingPauseAndPlayLeavesTheTransportPlaying() = runTest {
        withTransport(takeFrames = AudioTransportHarness.BUFFER_FRAMES * 4_000) {
            val connection = connection()
            connection.load(take)
            awaitEvent("Load")
            connection.play()
            awaitEvent("Play")
            awaitWrites(10)

            repeat(TOGGLES) {
                connection.pause()
                connection.play()
            }

            // Let every queued control call land, then ask the only question that matters.
            delay(SETTLE_MILLIS)
            val framesAtSettle = framesWritten()
            delay(OBSERVE_MILLIS)

            assertTrue(
                framesWritten() > framesAtSettle,
                "after $TOGGLES pause/play toggles ending in play(), audio must be flowing — the sink " +
                    "received nothing in ${OBSERVE_MILLIS}ms (stuck at $framesAtSettle frames). A stale " +
                    "pause landed after the final play and killed it."
            )
            assertEquals(
                "Play",
                events().last(),
                "the last thing the user asked for was play, so the last transport event must be Play, " +
                    "not a pause that overtook it. Full sequence: ${events()}"
            )
        }
    }

    /**
     * The same spam, but ending on pause. The transport must actually be stopped: a stale `play()`
     * landing last is the mirror failure, and leaves audio running after the user stopped it.
     */
    @Test
    fun spammingThatEndsOnPauseLeavesTheTransportPaused() = runTest {
        withTransport(takeFrames = AudioTransportHarness.BUFFER_FRAMES * 4_000) {
            val connection = connection()
            connection.load(take)
            awaitEvent("Load")
            connection.play()
            awaitEvent("Play")
            awaitWrites(10)

            repeat(TOGGLES) {
                connection.play()
                connection.pause()
            }

            delay(SETTLE_MILLIS)
            val framesAtSettle = framesWritten()
            delay(OBSERVE_MILLIS)

            assertEquals(
                framesAtSettle,
                framesWritten(),
                "the user's last action was pause, so no further audio may reach the sink"
            )
            assertEquals(
                "Pause",
                events().last(),
                "the last transport event must be the Pause the user asked for. Full sequence: ${events()}"
            )
        }
    }

    private companion object {
        const val TOGGLES = 20
        const val SETTLE_MILLIS = 600L
        const val OBSERVE_MILLIS = 300L
    }
}
