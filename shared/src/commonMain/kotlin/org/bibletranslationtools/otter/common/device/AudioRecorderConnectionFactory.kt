package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bibletranslationtools.shared.logging.launchLogged

/**
 * @param recorderScope where the recorder's read loop runs. [AudioRecorder] already accepted a scope
 *   — this passes one through, because constructing the recorder with its default meant the loop ran
 *   on `Dispatchers.Default` no matter what the caller wanted. A test could then not wait for a
 *   start/stop handover to land, which made `AudioConnectionTest.testRecorderExclusiveAccess` fail
 *   under a loaded suite (~40% of full runs) while passing alone.
 */
class AudioRecorderConnectionFactory(
    private var source: AudioSource,
    private val recorderScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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
     * [stopRecording] for a caller that is about to stop existing — screen teardown.
     *
     * Every host of a recorder connection releases the microphone from a teardown callback, and by
     * then its own scope is gone: a ViewModel's `viewModelScope` is cancelled when it is cleared, and
     * androidx cancels it *before* `onCleared` even runs. A release launched on that scope was
     * therefore either cancelled at its first suspension point or never started at all — leaving the
     * capture line open, which on Windows (where a capture line is exclusive) the next screen could
     * not allocate. See [AudioRecorder.releaseAsync] for the full account.
     *
     * So this runs on the factory's own scope, detached with [NonCancellable]. The actual release goes
     * through [AudioRecorder.releaseAsync], which is what makes the next [startRecording] wait for it
     * instead of racing it. The returned [Job] completes when the microphone is genuinely released;
     * callers in teardown ignore it, tests await it.
     *
     * Releasing is id-checked like [stopRecording]: a connection that has already lost the hardware to
     * another one must not release it on the way out. That is a real case now that both apps go
     * through connections — the record screen's teardown overlaps the playback page's insert session
     * during a navigation transition.
     */
    fun releaseRecording(connectionId: Int): Job =
        recorderScope.launchLogged(owner = this, context = NonCancellable) {
            val release = mutex.withLock {
                if (activeRecorderId != connectionId) return@withLock null
                activeRecorderId = null
                recorder.releaseAsync()
            }
            release?.join()
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