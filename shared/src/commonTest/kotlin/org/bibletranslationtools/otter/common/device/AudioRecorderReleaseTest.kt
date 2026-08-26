package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Releasing the microphone must not depend on the caller still being alive.
 *
 * `RecorderViewModel.cleanup()` runs from the record screen's `onDispose` and the ViewModel is
 * cleared moments later, which cancels `viewModelScope`. A release launched there suspends at
 * [AudioRecorder.stop]'s `cancelAndJoin` and was cancelled at that point — before `_source.close()`.
 * The read loop's own `finally` still stopped the source, so the capture line was left stopped but
 * open, and on Windows a capture line is exclusive: the next visit to the record screen could not
 * allocate one ("a line could not be allocated that supports the configuration").
 *
 * It alternated because the failed visit never started a read loop, so *its* teardown had no job to
 * join, never suspended, and ran to completion — closing the leaked line in time for the visit after
 * it. Every other visit worked.
 *
 * The recorder's read loop runs on a real dispatcher here, as it does in
 * [AudioConnectionTest.testRecorderExclusiveAccess] and for the same reason: [MockAudioSource]
 * returns a full buffer immediately, so on the test scheduler the loop never lets it go idle and the
 * test hangs rather than fails. Nothing below waits on a clock, though — `join()` and `isCompleted`
 * are both about actual completion.
 *
 * @see JvmAudioSourceReopenTest for the desktop half — a re-open releasing the line it replaces.
 */
class AudioRecorderReleaseTest {

    private val spec = AudioSpec()

    @Test
    fun releaseAsyncClosesTheSourceEvenWhenTheScopeItWasStartedOnIsCancelled() = runTest {
        val source = MockAudioSource()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val recorder = AudioRecorder(source, scope)

        recorder.start(spec)
        assertTrue(source.isOpen, "precondition: the mic is open")

        val release = recorder.releaseAsync()
        // What clearing the ViewModel does to the scope a teardown release used to run on.
        scope.cancel()
        release.join()

        assertFalse(
            source.isOpen,
            "the mic has to be released even though the scope the teardown ran on is gone — an " +
                "unreleased capture line is one Windows will not hand out again"
        )
    }

    @Test
    fun aReleaseLandsBeforeTheNextScreenOpensTheMic() = runTest {
        val source = MockAudioSource()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val recorder = AudioRecorder(source, scope)
        recorder.start(spec)

        // Leaving the record screen and re-entering it. The teardown release is asynchronous, so
        // without ordering it can land after the new screen has already opened the mic and close it
        // out from under it — the same handover race stop() documents, at screen scope.
        val release = recorder.releaseAsync()
        recorder.start(spec)

        assertTrue(
            release.isCompleted,
            "start() has to wait for a pending release rather than race it: a release that lands " +
                "afterwards closes the line the new screen just opened, and the meter sits dead"
        )
        assertTrue(source.isOpen, "so the mic the new screen opened is the one still open")
        assertTrue(source.isStarted, "and it is running, or the volume meter reads zero")

        scope.cancel()
    }

    @Test
    fun releaseRecordingClosesTheMicEvenWhenTheHostScopeIsCancelled() = runTest {
        val source = MockAudioSource()
        val recorderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val factory = AudioRecorderConnectionFactory(source, recorderScope)
        // Stands in for a viewModelScope: the host's scope, gone by the time teardown runs.
        val hostScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val connection = AudioRecorderConnection(id = 1, factory = factory, scope = hostScope)

        connection.startAndJoin(spec)
        assertTrue(source.isOpen, "precondition: the mic is open")

        hostScope.cancel()
        factory.releaseRecording(connectionId = 1).join()

        assertFalse(
            source.isOpen,
            "teardown has to release the mic on the factory's scope, not the host's — this is the " +
                "release that Orature's onCleared paths never ran at all"
        )
        assertFalse(factory.isActiveRecorder(1), "and the connection no longer holds the hardware")

        recorderScope.cancel()
    }

    @Test
    fun releaseRecordingIsIgnoredForAConnectionThatNoLongerHoldsTheMic() = runTest {
        val source = MockAudioSource()
        val recorderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val factory = AudioRecorderConnectionFactory(source, recorderScope)

        // The record screen opens the mic, then the playback page's insert takes it over — the two
        // overlap for the length of a navigation transition.
        factory.startRecording(connectionId = 1, spec = spec)
        factory.startRecording(connectionId = 2, spec = spec)

        // ...and only now does the record screen's teardown land.
        factory.releaseRecording(connectionId = 1).join()

        assertTrue(
            source.isOpen,
            "a connection must not release a mic it has already lost: unconditional teardown would " +
                "close the line out from under the session that took over"
        )
        assertTrue(factory.isActiveRecorder(2), "which still belongs to the connection that took it")

        recorderScope.cancel()
    }
}
