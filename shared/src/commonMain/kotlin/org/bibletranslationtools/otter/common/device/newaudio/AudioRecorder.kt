package org.bibletranslationtools.otter.common.device.newaudio

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

    suspend fun stop() {
        isPaused = false
        recordingJob?.cancel()
        recordingJob = null

        mutex.withLock {
            _source.stop()
            _source.close()
        }
    }

    fun isRecording(): Boolean = recordingJob?.isActive == true && !isPaused
}
