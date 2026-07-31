package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun setSource(newSource: AudioSource) = mutex.withLock {
        _source = newSource
    }

    suspend fun start(spec: AudioSpec) = mutex.withLock {
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

    fun isRecording(): Boolean = recordingJob?.isActive == true && !isPaused
}
