package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives the real audio transport — [AudioBufferPlayer], [AudioPlayerConnectionFactory] and
 * [AudioPlayerConnection] — through play / pause / seek / complete / replay against a
 * [PacedAudioSink], and records the **event sequence a consumer actually observes**.
 *
 * ### Why this is not a `runTest` virtual-time harness
 *
 * The playback loop has no `delay` in it and its only suspension point ([kotlinx.coroutines.sync.Mutex.lock])
 * doesn't suspend when uncontended, so on a virtual-time scheduler one `play()` runs a whole take to
 * completion inside a single pump. Every transport question worth asking ("what does pause mid-take
 * emit?", "does a seek restart at the target?", "is a second play swallowed?") is about the state
 * *during* playback, which virtual time cannot reach here. So the worker runs on
 * [Dispatchers.Default] and the sink paces itself in real time, exactly as production does — which
 * also means [AudioBufferPlayer.pause]'s internal `runBlocking` is exercised the way it really
 * behaves rather than against a blocked test thread.
 *
 * Timing is never *asserted*: every wait is a predicate over a [StateFlow] with a timeout and a
 * failure message that dumps what was actually seen, so a test can fail but not flake into passing.
 *
 * ### Why the events are recorded as labels
 *
 * [AudioPlayerEvent]'s members are `object`s with default `toString`, which makes a failed sequence
 * comparison unreadable. Labels ("Load", "Play", …) diff cleanly, and a typo in an expected label
 * fails loudly rather than silently matching.
 */
class AudioTransportHarness internal constructor(
    /** Length of [take], in frames. */
    val takeFrames: Int,
    millisPerWrite: Long
) {
    val sink = PacedAudioSink(millisPerWrite)
    val processor = IdentityAudioProcessor()

    /**
     * Real threads, not the test scheduler — see the class comment. Owned by the harness so
     * teardown can cancel it; a leaked playback job would otherwise outlive the test.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val factory = AudioPlayerConnectionFactory.createForScope(
        sink = sink,
        processor = processor,
        scope = scope
    )

    val worker: AudioBufferPlayer get() = factory.getPlayerWorker()

    /**
     * The harness's own real-dispatcher scope, for a collector a test needs running alongside playback.
     * The enclosing `runTest`'s scopes are on the virtual-time scheduler, which never advances while the
     * test body is suspended in real time.
     */
    val transportScope: CoroutineScope get() = scope

    /** The take under test. Frames are silence; only the frame accounting matters here. */
    val take = MockAudioFileReader(totalFrames = takeFrames)

    private val recorded = MutableStateFlow<List<String>>(emptyList())
    private val owners = MutableStateFlow<List<Int?>>(emptyList())
    private val subscribed = MutableStateFlow(false)

    private val recorder = scope.launch {
        worker.ownedEvents
            // onSubscription runs before any emission can be processed, so awaiting this flag is a
            // real guarantee that nothing was missed — the stream has no replay buffer.
            .onSubscription { subscribed.value = true }
            .collect { owned ->
                // Owner and label are appended in this order so a reader that has seen N labels can
                // always index the matching owner.
                owners.update { it + owned.owner }
                recorded.update { it + owned.event.transportLabel() }
            }
    }

    /** A second take, for the connection-handover cases. */
    fun newTake(frames: Int = takeFrames) = MockAudioFileReader(totalFrames = frames)

    /**
     * A connection wired to the shared worker. [Dispatchers.Default] as the control dispatcher
     * matches every production call site (see the Orature ViewModels), which notably does **not**
     * serialise control calls — so tests must await the effect of one call before making the next.
     */
    fun connection(id: Int = DEFAULT_CONNECTION_ID) =
        AudioPlayerConnection(id, factory, scope, Dispatchers.Default)

    // ── the observed event stream ────────────────────────────────────────────────────────

    /** Every event on the shared worker stream, whichever connection it belongs to. */
    fun events(): List<String> = recorded.value

    /**
     * The events belonging to one connection, by the owner stamped at emission — read off the harness's
     * single subscription, so there is no second subscriber to race.
     *
     * This asserts the worker's *attribution*. That [AudioPlayerConnection.events] is actually plugged
     * into it is a separate claim, and needs a collector on the real thing — see
     * `aConnectionsOwnEventStreamCarriesOnlyItsOwnEvents`.
     */
    fun eventsOf(connectionId: Int): List<String> {
        val owners = owners.value
        return recorded.value.filterIndexed { index, _ -> owners.getOrNull(index) == connectionId }
    }

    /** Suspends until [connectionId]'s own stream has recorded at least [count] events. */
    suspend fun awaitEventsOf(connectionId: Int, count: Int) = awaitFlow(
        recorded,
        "at least $count events for connection $connectionId"
    ) { eventsOf(connectionId).size >= count }

    suspend fun assertSequenceOf(connectionId: Int, vararg expected: String) {
        require(expected.isNotEmpty()) {
            "assertSequenceOf() with no expected events would assert nothing"
        }
        awaitEventsOf(connectionId, expected.size)
        assertEquals(
            expected.toList(),
            eventsOf(connectionId),
            "event sequence observed by connection $connectionId"
        )
    }

    /**
     * Awaits exactly [expected].size events and asserts the whole recorded stream equals [expected].
     * Asserting the *whole* stream (rather than a suffix) is what makes a spurious extra event —
     * a duplicate `Pause`, a phantom `Complete` from a previous session — fail the test.
     *
     * Call it cumulatively: each step of a scenario re-states the full sequence so far, which both
     * documents the scenario and serialises it, since the next control call is only made once the
     * previous one's events have landed.
     */
    suspend fun assertSequence(vararg expected: String) {
        require(expected.isNotEmpty()) {
            "assertSequence() with no expected events would assert nothing"
        }
        awaitEventCount(expected.size)
        assertEquals(expected.toList(), events(), "transport event sequence")
    }

    /** Suspends until at least one [label] event lands beyond those already recorded. */
    suspend fun awaitEvent(label: String) {
        val seen = events().size
        // Predicate over the *growing* list, never over an emission, so StateFlow's conflation
        // cannot drop the event we are waiting for.
        awaitFlow(recorded, "a $label event after the first $seen") { current ->
            current.size > seen && label in current.drop(seen)
        }
    }

    suspend fun awaitEventCount(count: Int): List<String> =
        awaitFlow(recorded, "at least $count transport events") { it.size >= count }

    /**
     * Asserts the stream stays quiet for [quietMillis]. This is how "no `Complete` after a pause"
     * and "the second `play()` was swallowed" are pinned — the absence of an event is the assertion.
     */
    suspend fun assertNoFurtherEvents(quietMillis: Long = QUIET_MILLIS) {
        val before = events()
        delay(quietMillis)
        assertEquals(before, events(), "no further transport events expected within ${quietMillis}ms")
    }

    // ── the sink ────────────────────────────────────────────────────────────────────────

    suspend fun awaitWrites(count: Int) =
        awaitFlow(sink.writes, "$count buffers to reach the sink") { it >= count }

    fun framesWritten(): Long = sink.framesWritten.value

    fun sinkCalls(): List<String> = sink.calls.value

    fun assertFramesWritten(expected: Long, message: String) =
        assertEquals(expected, framesWritten(), message)

    /**
     * Asserts the sink received between [atLeast] and [atMost] frames. Used where an exact count is
     * genuinely not deterministic — a resume re-plays whatever was already written but not yet
     * accounted for at the moment the position was read — and the *bug* being guarded against
     * (replaying from zero, or losing the tail) is orders of magnitude outside the tolerance.
     */
    fun assertFramesWrittenBetween(atLeast: Long, atMost: Long, message: String) {
        val actual = framesWritten()
        assertTrue(actual in atLeast..atMost, "$message (expected $atLeast..$atMost, was $actual)")
    }

    // ── the worker's reported position ──────────────────────────────────────────────────

    fun positionInFrames(): Long = worker.getLocationInFrames()

    // ── composite control helpers ───────────────────────────────────────────────────────

    /**
     * Loads and starts playback, awaiting each step. The two calls must not be issued back to back:
     * they land on separate [Dispatchers.Default] coroutines, and if `load()` runs second it resets
     * `playRequested` to false and the connection reports itself as not playing.
     */
    suspend fun loadAndPlay(connection: AudioPlayerConnection, reader: AudioFileReader = take) {
        connection.load(reader)
        awaitEvent("Load")
        connection.play()
        awaitEvent("Play")
    }

    // ── internals ───────────────────────────────────────────────────────────────────────

    /** The recorder must be subscribed before the first event: the stream has no replay buffer. */
    internal suspend fun awaitRecorderSubscription() {
        awaitFlow(subscribed, "the event recorder to subscribe") { it }
    }

    internal fun shutdown() {
        sink.releaseStop()
        recorder.cancel()
        worker.release()
        scope.cancel()
    }

    private suspend fun <T> awaitFlow(
        flow: StateFlow<T>,
        what: String,
        predicate: (T) -> Boolean
    ): T = withTimeoutOrNull(TIMEOUT_MILLIS) { flow.first(predicate) }
        ?: fail(
            "timed out after ${TIMEOUT_MILLIS}ms waiting for $what" +
                "; events so far: ${events()}" +
                "; buffers written: ${sink.writes.value}" +
                "; sink calls: ${sinkCalls()}"
        )

    companion object {
        /** Frames the playback loop moves per [AudioSink.write], given [IdentityAudioProcessor]. */
        const val BUFFER_FRAMES = 1024

        /** ~100ms of playback at one millisecond per buffer: long enough to interrupt mid-take. */
        const val DEFAULT_TAKE_FRAMES = BUFFER_FRAMES * 100

        const val DEFAULT_CONNECTION_ID = 1

        private const val TIMEOUT_MILLIS = 5_000L
        private const val QUIET_MILLIS = 100L
    }
}

/**
 * Runs [block] against a fresh transport on real dispatchers, then tears it down.
 *
 * The whole body runs in [Dispatchers.Default] so that timeouts and quiet windows are real time even
 * when the enclosing test is a `runTest` (whose virtual clock would otherwise fire them instantly).
 */
suspend fun withTransport(
    takeFrames: Int = AudioTransportHarness.DEFAULT_TAKE_FRAMES,
    millisPerWrite: Long = 1L,
    block: suspend AudioTransportHarness.() -> Unit
) = withContext(Dispatchers.Default) {
    val harness = AudioTransportHarness(takeFrames, millisPerWrite)
    harness.awaitRecorderSubscription()
    try {
        harness.block()
    } finally {
        harness.shutdown()
    }
}

internal fun AudioPlayerEvent.transportLabel(): String = when (this) {
    AudioPlayerEvent.Load -> "Load"
    AudioPlayerEvent.Play -> "Play"
    AudioPlayerEvent.Pause -> "Pause"
    AudioPlayerEvent.Stop -> "Stop"
    AudioPlayerEvent.Complete -> "Complete"
    is AudioPlayerEvent.Error -> "Error($message)"
}
