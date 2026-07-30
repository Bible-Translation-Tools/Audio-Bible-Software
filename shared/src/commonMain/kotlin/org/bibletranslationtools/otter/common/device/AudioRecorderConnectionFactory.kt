package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AudioRecorderConnectionFactory(
    private var source: AudioSource
) {
    private val recorder = AudioRecorder(source)
    private var activeRecorderId: Int? = null
    private val mutex = Mutex()

    fun isActiveRecorder(id: Int): Boolean = activeRecorderId == id

    suspend fun startRecording(connectionId: Int, spec: AudioSpec) = mutex.withLock {
        if (activeRecorderId != null && activeRecorderId != connectionId) {
            recorder.stop()
        }
        activeRecorderId = connectionId
        recorder.start(spec)
    }

    suspend fun stopRecording(connectionId: Int) = mutex.withLock {
        if (activeRecorderId == connectionId) {
            recorder.stop()
            activeRecorderId = null
        }
    }

    /**
     * Updated to suspend and safely update the recorder worker.
     */
    suspend fun updateHardwareSource(newSource: AudioSource) = mutex.withLock {
        val wasRecording = recorder.isRecording()

        // 1. Shutdown old hardware
        source.stop()
        source.close()

        // 2. Update local reference
        this.source = newSource

        // 3. Update the internal worker's reference safely
        recorder.setSource(newSource)

        // 4. Restart if we were mid-session using the stored spec
        if (wasRecording) {
            recorder.start(recorder.currentSpec)
        }
    }

    fun getRecorderWorker() = recorder
}