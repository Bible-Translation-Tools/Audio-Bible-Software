package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * @param recorderScope where the recorder's read loop runs. [AudioRecorder] already accepted a scope
 *   — this passes one through, because constructing the recorder with its default meant the loop ran
 *   on `Dispatchers.Default` no matter what the caller wanted. A test could then not wait for a
 *   start/stop handover to land, which made `AudioConnectionTest.testRecorderExclusiveAccess` fail
 *   under a loaded suite (~40% of full runs) while passing alone.
 */
class AudioRecorderConnectionFactory(
    private var source: AudioSource,
    recorderScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val recorder = AudioRecorder(source, recorderScope)
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