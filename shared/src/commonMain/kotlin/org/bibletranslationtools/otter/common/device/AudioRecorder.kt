package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bibletranslationtools.shared.logging.launchLogged

class AudioRecorder(
    source: AudioSource,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    var currentSpec: AudioSpec = AudioSpec()
        private set

    // Generous buffer so a transient slow collector (disk-write hiccup, GC pause) never makes emit()
    // suspend and stall the read loop below — a stalled read lets the hardware input line overrun and
    // the OS drops samples, which clips words in the recording. 512 packets ≈ 6s of slack.
    private val _audioStream = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    val audioStream = _audioStream.asSharedFlow()

    private val mutex = Mutex()
    private var _source: AudioSource = source
    private var recordingJob: Job? = null
    private var isPaused = false

    /** The most recent [releaseAsync], so the next [start] can wait for it. See [releaseAsync]. */
    @Volatile
    private var pendingRelease: Job? = null

    suspend fun setSource(newSource: AudioSource) = mutex.withLock {
        _source = newSource
    }

    suspend fun start(spec: AudioSpec) {
        // A release scheduled by the screen this one is replacing has to land BEFORE the mic is
        // opened again, or it lands after and closes the line out from under us — the same handover
        // race [stop] documents, one level up. Callers only guarantee the *calls* are ordered (both
        // happen on the UI thread, dispose before the next screen's init); this is what makes the
        // *effects* ordered, without the caller having to suspend during teardown.
        pendingRelease?.join()
        pendingRelease = null
        startLocked(spec)
    }

    private suspend fun startLocked(spec: AudioSpec) = mutex.withLock {
        this.currentSpec = spec
        if (recordingJob?.isActive == true && !isPaused) return@withLock

        if (!isPaused) {
            _source.open(spec)
        }

        isPaused = false
        _source.start()

        recordingJob = scope.launch {
            val bufferSize = 1024
            val buffer = ByteArray(bufferSize)

            try {
                while (isActive && !isPaused) {
                    val read = mutex.withLock {
                        _source.read(buffer, 0, buffer.size)
                    }

                    if (read > 0) {
                        _audioStream.emit(buffer.copyOf(read))
                    }
                }
            } catch (e: Exception) {
                // Handle or log recording error
            } finally {
                mutex.withLock { _source.stop() }
            }
        }
    }

    fun pause() {
        isPaused = true
        recordingJob?.cancel()
        scope.launch {
            mutex.withLock { _source.stop() }
        }
    }

    /**
     * Stops recording and waits for the read loop to finish unwinding before releasing the source.
     *
     * The join is load-bearing, not tidiness. The read loop's `finally` runs
     * `mutex.withLock { _source.stop() }`, so a bare `cancel()` returns while that cleanup is still
     * pending on [scope]'s dispatcher. When one recorder is swapped for another —
     * `AudioRecorderConnectionFactory.startRecording` calls `stop()` then `start()` — the old loop's
     * cleanup could land *after* the new recording had already called `_source.start()`, stopping the
     * source out from under it. That is what made `AudioConnectionTest.testRecorderExclusiveAccess`
     * fail under a loaded suite: not a slow test, a real handover race.
     *
     * No deadlock: the mutex is taken only after the join, so the unwinding loop can still acquire it.
     */
    suspend fun stop() {
        isPaused = false
        val job = recordingJob
        recordingJob = null
        job?.cancelAndJoin()

        mutex.withLock {
            _source.stop()
            _source.close()
        }
    }

    /**
     * [stop], for a caller that is about to stop existing — screen teardown.
     *
     * Releasing the microphone must not depend on the caller's scope outliving the call, and from a
     * teardown path it never does. `RecorderViewModel.cleanup()` runs from `onDispose`, and the
     * ViewModel is cleared moments later; a `viewModelScope.launch { recorder.stop() }` there gets
     * cancelled at its first suspension point, which is [stop]'s `cancelAndJoin` — *before*
     * `_source.close()`. The read loop's own `finally` still stops the source, so the line is left
     * stopped but open, and on Windows a capture line is exclusive: re-entering the record screen
     * then failed with "cannot allocate a line supporting this configuration".
     *
     * It failed every *other* time for the same reason it failed at all. The visit that failed to
     * open never started a read loop, so its teardown had no job to join, so nothing suspended and
     * the release ran to completion synchronously — closing the leaked line and letting the visit
     * after that succeed.
     *
     * So this runs on the recorder's own scope, detached from it with [NonCancellable] so that even
     * a caller-supplied scope being torn down cannot interrupt the release half-way. The returned
     * [Job] is only for tests that need to await it; [start] already waits for it on its own.
     */
    fun releaseAsync(): Job {
        val job = scope.launchLogged(owner = this, context = NonCancellable) { stop() }
        pendingRelease = job
        return job
    }

    fun isRecording(): Boolean = recordingJob?.isActive == true && !isPaused
}
