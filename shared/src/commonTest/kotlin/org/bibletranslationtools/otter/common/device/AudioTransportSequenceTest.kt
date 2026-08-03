package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.device.AudioTransportHarness.Companion.BUFFER_FRAMES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The **observable event sequence** of [AudioBufferPlayer] across play / pause / seek / complete /
 * replay, plus the audio that did or did not reach the hardware alongside it.
 *
 * Nothing else in this repo tests transport *state transitions*: the other audio tests check
 * arithmetic (`AudioTimelineTest`, `WaveformPeakCacheTest`, `PlaybackDisplayClockSmoothnessTest`) or a
 * single regression each. Every transport bug this project has shipped, though, was a wrong
 * *sequence* — a `Complete` with no audio behind it, a `play()` that emitted nothing, a `Complete`
 * from a finished session landing on the current one. Consumers drive their entire UI state off this
 * stream, so an event that is missing, doubled, or out of order is a user-visible failure.
 *
 * Two kinds of test live here and they are labelled as such:
 *  - **contract** — this is what the transport must do, and a regression is a bug;
 *  - **characterisation** — this is what the transport *currently* does, including behaviour that is
 *    arguably wrong. Pinning it means changing it becomes a visible decision instead of an accident,
 *    and it means the layers that compensate for it cannot be removed silently.
 *
 * See [AudioTransportHarness] for why these run on real dispatchers rather than virtual time.
 */
class AudioTransportSequenceTest {

    // ── playing through ─────────────────────────────────────────────────────────────────

    /** Contract. The baseline sequence, and the only one that ends in a legitimate `Complete`. */
    @Test
    fun playingATakeThroughEmitsLoadPlayCompleteExactlyOnce() = runTest {
        withTransport {
            worker.load(take)
            worker.play()

            assertSequence("Load", "Play", "Complete")
            // A `Complete` is only honest if the audio behind it was actually delivered.
            assertFramesWritten(takeFrames.toLong(), "the whole take should have reached the sink")
            assertEquals(takeFrames.toLong(), positionInFrames(), "position should land on the end")
            // No second `Complete` from the job's own teardown.
            assertNoFurtherEvents()
        }
    }

    /**
     * Characterisation of the bug shape this harness exists to catch.
     *
     * `play()` on an exhausted reader emits `Play` and then immediately `Complete`, having delivered
     * no audio at all: the loop breaks on its first iteration (`!currentReader.hasRemaining()`)
     * without reading, then still falls into the drain block below the loop and completes. To a
     * consumer this is indistinguishable from a take that played, which is why replaying a finished
     * take once jumped to the end and flipped the transport back to paused.
     *
     * The worker is left as-is; [AudioPlayerConnection.play] is the layer that prevents this, by
     * rewinding a finished take before connecting. This test is what makes that compensation
     * load-bearing rather than incidental — and `assertFramesWritten(0)` is the assertion the event
     * sequence alone cannot make.
     */
    @Test
    fun playingAnExhaustedReaderCompletesWithoutWritingAnyAudio() = runTest {
        withTransport {
            worker.load(take)
            worker.seek(takeFrames.toLong()) // where a take that has played through is left

            worker.play()

            assertSequence("Load", "Play", "Complete")
            assertFramesWritten(0L, "an exhausted reader must not have produced audio")
        }
    }

    // ── pausing ─────────────────────────────────────────────────────────────────────────

    /** Contract. A pause mid-take must not look like a finished take. */
    @Test
    fun pausingMidTakeEmitsPauseAndNeverCompletes() = runTest {
        withTransport {
            worker.load(take)
            worker.play()
            awaitWrites(15)

            worker.pause()

            assertSequence("Load", "Play", "Pause")
            // The absence of `Complete` is the point: a consumer that saw one here would reset its
            // transport to the start of the take the user is standing in the middle of.
            assertNoFurtherEvents()

            val framesAtPause = framesWritten()
            assertTrue(
                framesAtPause < takeFrames,
                "pause should have stopped short of the end (was $framesAtPause of $takeFrames)"
            )
            assertTrue(
                positionInFrames() in 1 until takeFrames.toLong(),
                "the paused position should be inside the take, was ${positionInFrames()}"
            )
        }
    }

    /**
     * Contract. Pause stops and flushes the hardware but must not close it: the flush is what
     * discards already-queued audio so a resume does not replay the buffer, and keeping the line open
     * is what makes the resume cheap.
     */
    @Test
    fun pausingStopsAndFlushesTheHardwareWithoutClosingIt() = runTest {
        withTransport {
            worker.load(take)
            worker.play()
            awaitWrites(15)
            val callsBeforePause = sinkCalls().size

            worker.pause()
            assertSequence("Load", "Play", "Pause")

            val duringPause = sinkCalls().drop(callsBeforePause)
            // Not an ordered comparison: the cancelled playback job's own `finally` block races the
            // pause and may add a second `stop`. Both stops are harmless; the flush is mandatory.
            assertTrue("stop" in duringPause, "pause should stop the sink, saw $duringPause")
            assertTrue("flush" in duringPause, "pause should flush the sink, saw $duringPause")
            assertFalse("close" in sinkCalls(), "pause must not close the hardware line")
        }
    }

    /**
     * Contract. Resume emits a second `Play` and plays each remaining frame exactly once — no
     * restart from zero, and no lost tail. Both failures produce a `Complete` at the right time, so
     * the frame count is the only thing that separates them.
     */
    @Test
    fun resumingAfterPauseEmitsASecondPlayAndPlaysEveryFrameOnce() = runTest {
        withTransport {
            worker.load(take)
            worker.play()
            awaitWrites(15)
            worker.pause()
            assertSequence("Load", "Play", "Pause")

            // Issued immediately, with the cancelled job still unwinding: pause ends the session, so
            // the resume is accepted rather than dropped. See
            // aPlayIssuedWhileTheFinishedJobIsUnwindingIsQueuedNotDropped.
            worker.play()

            assertSequence("Load", "Play", "Pause", "Play", "Complete")
            assertFramesWritten(
                takeFrames.toLong(),
                "resume should have played the remainder, not the whole take again"
            )
            assertEquals(takeFrames.toLong(), positionInFrames(), "position should land on the end")
        }
    }

    // ── seeking ─────────────────────────────────────────────────────────────────────────

    /**
     * Characterisation. A seek during playback restarts the play session, so the stream shows two
     * consecutive `Play` events with **no** `Pause` between them, even though the sink really was
     * stopped and flushed in between.
     *
     * That happens to be survivable — a consumer toggling on Play/Pause ends up in the right state —
     * but it means the stream carries no signal that a seek occurred. That silence is exactly why
     * [org.bibletranslationtools.shared.audio.engine.PlaybackDisplayClock] needs its settle latch and
     * two-second safety valve to discover where playback actually went.
     */
    @Test
    fun seekingWhilePlayingRestartsAtTheTargetWithNoPauseEvent() = runTest {
        withTransport {
            val target = (takeFrames - BUFFER_FRAMES * 10).toLong()
            worker.load(take)
            worker.play()
            awaitWrites(5)

            worker.seek(target)

            assertSequence("Load", "Play", "Play", "Complete")
            assertEquals(takeFrames.toLong(), positionInFrames(), "position should land on the end")

            val written = framesWritten()
            assertTrue(
                written >= takeFrames - target,
                "the tail after the seek target should have played (wrote $written)"
            )
            // A seek that ignored its target, or restarted from zero, would have written the whole
            // take or more; the frames between the seek origin and the target must be skipped.
            assertTrue(
                written < takeFrames,
                "the skipped middle of the take must not have been played (wrote $written of $takeFrames)"
            )
        }
    }

    // ── play requests during another session ────────────────────────────────────────────

    /**
     * Contract. A play during playback is genuinely redundant — the session it would start is the
     * session already running — so it is a silent no-op rather than a second `Play`.
     *
     * This is the one case the old `playbackJob?.isActive` guard got right, and the only case that
     * should still be dropped.
     */
    @Test
    fun aRedundantPlayDuringPlaybackIsANoOp() = runTest {
        // A long take deliberately: the quiet window that proves nothing was emitted has to fit
        // inside the take, or the take's own `Complete` would break it.
        withTransport(takeFrames = BUFFER_FRAMES * 400) {
            worker.load(take)
            worker.play()
            awaitWrites(10)

            worker.play() // no event, no error

            assertSequence("Load", "Play")
            assertNoFurtherEvents()

            // And the take carries on to a single, normal completion.
            assertSequence("Load", "Play", "Complete")
            assertFramesWritten(takeFrames.toLong(), "the take should still play through exactly once")
        }
    }

    /**
     * Contract, and the regression guard for the swallowed replay.
     *
     * `Complete` is emitted from inside the playback job, *before* its `finally` block runs, so the
     * job is still alive when consumers react to it. Guarding `play()` on `playbackJob?.isActive`
     * therefore dropped exactly the request most likely to be made — a replay on completion — with no
     * event and no error. The guard is now the playback *session*: the completed session has released
     * production, so the request is accepted.
     *
     * Accepted is not the same as immediate. The new session still waits for the old one's teardown,
     * because that teardown stops the sink and would otherwise land after the new session started it.
     * [PacedAudioSink.holdStop] freezes the old job inside that teardown, which turns a
     * microsecond-wide race into something that can actually be asserted: the request survives the
     * whole time the old session is unwinding, and the sink is started only after it was stopped.
     */
    @Test
    fun aPlayIssuedWhileTheFinishedJobIsUnwindingIsQueuedNotDropped() = runTest {
        withTransport {
            worker.load(take)
            worker.play()
            sink.holdStop() // the job will block in its teardown, after emitting Complete
            assertSequence("Load", "Play", "Complete")
            val callsAtComplete = sinkCalls().size

            worker.play() // accepted, but must not start while the old session holds the hardware

            assertNoFurtherEvents()

            sink.releaseStop()

            // The request was held, not lost. (This second session finds an exhausted reader, so it
            // completes without audio — that hazard is the connection's to prevent, and
            // AudioPlayerConnectionTransportTest covers the rewind that does it.)
            assertSequence("Load", "Play", "Complete", "Play", "Complete")
            assertEquals(
                listOf("stop", "start"),
                sinkCalls().drop(callsAtComplete).take(2),
                "the new session must start the sink only after the old session stopped it"
            )
        }
    }

    /**
     * Contract. A `Complete` may only describe the session that is actually current.
     *
     * Seeking to the end while playing used to produce two: the cancelled job resumed after its
     * in-flight write, found `isPaused` back to `false` (the `play()` inside `seek()` had reset it),
     * saw a reader with nothing remaining, and emitted a `Complete` of its own — on the same shared
     * stream, indistinguishable from the real one. A consumer that resets its transport on `Complete`
     * did it twice, the second time against a session that had barely begun.
     *
     * The sink is deliberately slowed here so the cancelled job is *inside* a write when the seek
     * lands, which is the state that produced the duplicate.
     */
    @Test
    fun aSupersededSessionCannotCompleteTheSessionThatReplacedIt() = runTest {
        withTransport(takeFrames = BUFFER_FRAMES * 10, millisPerWrite = 50L) {
            worker.load(take)
            worker.play()
            awaitWrites(2) // now blocked inside the third write, for ~50ms

            worker.seek(takeFrames.toLong())

            // One Play for the session the seek started, one Complete, and nothing from the session
            // it replaced.
            assertSequence("Load", "Play", "Play", "Complete")
            assertNoFurtherEvents()
        }
    }

    // ── session identity ────────────────────────────────────────────────────────────────

    /**
     * Contract. Every transport transition ends the current session, and playing does not silently
     * re-identify itself as it goes — which is what makes an id captured at `play()` usable for
     * deciding whether a later event belongs to it.
     */
    @Test
    fun everyTransportTransitionEndsThePlaybackSession() = runTest {
        withTransport {
            val initial = worker.playbackSession
            worker.load(take)
            val loaded = worker.playbackSession
            worker.play()
            val playing = worker.playbackSession
            awaitWrites(10)
            assertEquals(playing, worker.playbackSession, "playing on must not change the session")

            worker.pause()
            val paused = worker.playbackSession
            worker.seek(0)
            val seeked = worker.playbackSession

            val ids = listOf(initial, loaded, playing, paused, seeked)
            assertEquals(ids.size, ids.distinct().size, "each transition should end the session: $ids")
            assertEquals(ids.sorted(), ids, "session ids should advance, never go backwards: $ids")
        }
    }
}
