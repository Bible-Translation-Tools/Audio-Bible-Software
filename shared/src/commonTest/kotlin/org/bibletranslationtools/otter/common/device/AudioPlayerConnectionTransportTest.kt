package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.device.AudioTransportHarness.Companion.BUFFER_FRAMES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The same transport scenarios as [AudioTransportSequenceTest], driven through
 * [AudioPlayerConnection] and a real [AudioPlayerConnectionFactory] instead of the worker directly —
 * so what is asserted is what an app actually observes.
 *
 * This is the tier where [AudioPlayerConnectionReplayTest] stops: that test verifies, with mocks,
 * *which position* `play()` connects at. These tests verify that the resulting playback really
 * happened, by counting the audio that reached the hardware. A connection can connect at exactly the
 * right frame and still deliver nothing, which is precisely the failure that shipped.
 *
 * Sequences here are longer than the worker's because [AudioPlayerConnectionFactory.connect] does
 * `pause` + `load` + `seek` whenever the connection, reader, or start position changes. That is
 * pinned rather than corrected — see the individual tests.
 */
class AudioPlayerConnectionTransportTest {

    /**
     * Contract. The worker stream carries a leading `Pause` before anything has played — `connect()`
     * unconditionally pauses the hardware to take it, and [AudioBufferPlayer.pause] emits whether or
     * not something was playing — but that `Pause` belongs to whoever held the hardware before, so the
     * connection taking over never sees it. Its own view is just its take: load, play, finish.
     */
    @Test
    fun aFreshLoadAndPlayRunsTheTakeToCompletion() = runTest {
        withTransport {
            val connection = connection()

            loadAndPlay(connection)

            assertSequence("Pause", "Load", "Play", "Complete")
            assertSequenceOf(1, "Load", "Play", "Complete")
            assertFramesWritten(takeFrames.toLong(), "the whole take should have reached the sink")
        }
    }

    /**
     * Contract, and the end-to-end guard for the replay bug.
     *
     * Replaying a finished take has to deliver the audio a second time. It very nearly cannot: the
     * connection's own `lastPosition` is still 0 (nothing updated it during playback), so the
     * pre-connect rewind does not fire; `connect()` then sees an unchanged position and skips the
     * reload; and the worker is holding a reader with nothing remaining, which would emit
     * `Play` + `Complete` with no audio at all. What saves it is the post-connect probe of the
     * worker's own position in [AudioPlayerConnection.play], which rewinds the worker to 0.
     *
     * Delete either rewind and the frame count — not the event sequence — is what catches it.
     */
    @Test
    fun replayingAFinishedTakeDeliversTheWholeTakeAgain() = runTest {
        withTransport {
            val connection = connection()
            loadAndPlay(connection)
            assertSequence("Pause", "Load", "Play", "Complete")

            // Issued the moment `Complete` lands, which is exactly when a replay-on-completion would
            // fire and used to be dropped — see AudioTransportSequenceTest.
            connection.play()

            assertSequence("Pause", "Load", "Play", "Complete", "Play", "Complete")
            assertFramesWritten(
                takeFrames.toLong() * 2,
                "the replay should have delivered the take again, not completed silently"
            )
        }
    }

    /**
     * Contract on the audio, characterisation on the events.
     *
     * Pausing and resuming must play every frame once — the audible failure modes are restarting
     * from zero and dropping the tail, both of which the frame count catches and the event sequence
     * does not.
     *
     * Both of the artifacts this used to pin are gone. A resume no longer goes through `connect()` — the
     * hardware still holds our audio and the reader is where the loop left it, so it is a plain `start()` —
     * so there is no second consecutive `Pause` and no redundant `Load`, and no hardware reload costing
     * ~250ms of silence. The frame count is now exact rather than bracketed, because nothing is discarded
     * and nothing is replayed.
     */
    @Test
    fun pausingAndResumingMidTakePlaysEveryFrameOnce() = runTest {
        withTransport(takeFrames = MID_TAKE_FRAMES) {
            val connection = connection()
            loadAndPlay(connection)
            awaitWrites(15)

            connection.pause()
            assertSequence("Pause", "Load", "Play", "Pause")
            assertNoFurtherEvents()

            connection.play()

            assertSequence("Pause", "Load", "Play", "Pause", "Play", "Complete")
            assertFramesWritten(
                takeFrames.toLong(),
                "resume should have played the remainder exactly once: not the take over again, and not " +
                    "short of the end"
            )
            assertEquals(takeFrames.toLong(), positionInFrames(), "position should land on the end")
        }
    }

    /**
     * Characterisation. [AudioPlayerConnection.stop] surfaces on the stream as `Pause`, and
     * [AudioPlayerEvent.Stop] is never emitted by anything — a consumer waiting for it waits forever.
     *
     * The rewind is real, though: `stop()` seeks the reader back to 0, so the next `play()` delivers
     * the whole take again even though `connect()` sees an unchanged start position and skips its
     * reload.
     */
    @Test
    fun stopEmitsPauseRatherThanStopAndRewindsTheTake() = runTest {
        withTransport(takeFrames = MID_TAKE_FRAMES) {
            val connection = connection()
            loadAndPlay(connection)
            awaitWrites(15)

            connection.stop()
            assertSequence("Pause", "Load", "Play", "Pause")
            assertNoFurtherEvents()
            // Quiet stream means the sink is idle, so this count cannot move under us.
            val framesAtStop = framesWritten()
            assertTrue(
                framesAtStop < takeFrames,
                "stop should have interrupted the take (wrote $framesAtStop of $takeFrames)"
            )

            connection.play()

            assertSequence("Pause", "Load", "Play", "Pause", "Play", "Complete")
            assertFramesWritten(
                framesAtStop + takeFrames,
                "playing after a stop should deliver the take from the beginning"
            )
        }
    }

    /**
     * Contract. A takeover tells the evicted connection that it stopped, and tells it nothing else.
     *
     * One worker serves every player — playback, source audio, narration, take previews — over one
     * event stream. That stream used to be handed to each connection whole, so a host saw every other
     * player's transitions and could not tell them apart from its own. The damage was measured in
     * [org.bibletranslationtools.shared.audio.engine.PlaybackDisplayJumpTest]: another player's
     * `Complete` parked this host's display at the end of a take that was still playing.
     *
     * The split has to fall in exactly one place. The eviction `Pause` belongs to the connection being
     * evicted — it really did stop, and this is the only thing that tells it so — while the `Load` and
     * `Play` that follow belong to the connection taking over.
     */
    @Test
    fun aTakeoverPausesTheEvictedConnectionAndTellsItNothingElse() = runTest {
        withTransport(takeFrames = MID_TAKE_FRAMES) {
            val first = connection(id = 1)
            val second = connection(id = 2)
            loadAndPlay(first)
            awaitWrites(10)
            assertTrue(first.isPlaying(), "the first connection should hold the hardware")

            second.load(newTake())
            awaitEvent("Load")
            assertSequence("Pause", "Load", "Play", "Pause", "Load")

            second.play()
            assertSequence("Pause", "Load", "Play", "Pause", "Load", "Play")

            // The whole point: the same six events, split by who they belong to.
            assertSequenceOf(1, "Load", "Play", "Pause")
            assertSequenceOf(2, "Load", "Play")

            assertTrue(factory.isActiveConnection(2), "the second connection should own the hardware")
            assertFalse(factory.isActiveConnection(1), "the first connection should be evicted")
            assertFalse(first.isPlaying(), "the evicted connection must not report itself as playing")
        }
    }

    /**
     * Contract on the wiring, collected from the real [AudioPlayerConnection.events].
     *
     * Every other test here reads attribution off the harness's own subscription to the worker, which
     * proves the events are *tagged* correctly but not that a connection is plugged into its own tag.
     * That difference is one line in [AudioPlayerConnection], and without this test that line could be
     * reverted to the raw shared stream with the whole suite still green — verified by doing exactly
     * that.
     */
    @Test
    fun aConnectionsOwnEventStreamCarriesOnlyItsOwnEvents() = runTest {
        withTransport(takeFrames = MID_TAKE_FRAMES) {
            val first = connection(id = 1)
            val second = connection(id = 2)
            val firstSaw = MutableStateFlow<List<String>>(emptyList())
            val secondSaw = MutableStateFlow<List<String>>(emptyList())
            transportScope.launch {
                first.events.collect { e -> firstSaw.update { it + e.transportLabel() } }
            }
            transportScope.launch {
                second.events.collect { e -> secondSaw.update { it + e.transportLabel() } }
            }

            first.load(take)
            awaitEvent("Load") // the worker's owner is connection 1 from here

            // Subscription handshake: retry an idempotent event until a collector proves it is live.
            // A fixed delay would either flake or pad the suite; retrying can do neither. Pausing an
            // idle worker only stops and flushes a stopped sink.
            while (firstSaw.value.none { it == "Pause" }) {
                worker.pause()
                delay(10)
            }
            val firstBaseline = firstSaw.value.size

            // Connection 2 takes the hardware and plays a short clip through to its end.
            loadAndPlay(second, newTake(frames = BUFFER_FRAMES * 20))
            awaitEvent("Complete")
            awaitEventsOf(2, 3)

            assertEquals(
                listOf("Pause"),
                firstSaw.value.drop(firstBaseline),
                "connection 1 should receive its eviction Pause and nothing of connection 2's playback"
            )
            assertEquals(
                listOf("Load", "Play", "Complete"),
                secondSaw.value,
                "connection 2 should receive its own load, play and completion"
            )
        }
    }

    /**
     * Contract. The evicted connection must not receive the successor's completion — the event that,
     * applied to the wrong host, parks a mid-playback display at the end of its take.
     */
    @Test
    fun aConnectionDoesNotReceiveAnotherConnectionsCompletion() = runTest {
        withTransport(takeFrames = MID_TAKE_FRAMES) {
            val first = connection(id = 1)
            val second = connection(id = 2)
            loadAndPlay(first)
            awaitWrites(10)

            // A short clip on the other connection, played to its end.
            loadAndPlay(second, newTake(frames = BUFFER_FRAMES * 20))
            awaitEvent("Complete")

            assertSequenceOf(2, "Load", "Play", "Complete")
            assertSequenceOf(1, "Load", "Play", "Pause")
            assertEquals(
                0,
                eventsOf(1).count { it == "Complete" },
                "the evicted connection's take never finished, so it must see no Complete"
            )
        }
    }

    private companion object {
        /**
         * Long enough that the take cannot finish while the scenario is still being set up. Every test
         * below whose premise is "playback is still in flight" must outlast its own control calls, or a
         * loaded machine turns the premise false and the test fails for a reason that is not a bug.
         */
        const val MID_TAKE_FRAMES = BUFFER_FRAMES * 600
    }
}
