package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.bibletranslationtools.shared.logging.launchLogged

class AudioRecorderConnection(
    private val id: Int,
    private val factory: AudioRecorderConnectionFactory,
    private val scope: CoroutineScope
) : IAudioRecorder {

    override fun start(spec: AudioSpec) {
        // Fire-and-forget, so a device that will not open can only be logged here, never surfaced. Hosts
        // that show the user an error await [startAndJoin] instead.
        scope.launchLogged(owner = this) { startAndJoin(spec) }
    }

    /**
     * [start], awaited — so the caller finds out whether the microphone actually opened.
     *
     * The fire-and-forget [start] cannot say. Opening a capture device is the operation that fails in
     * the field (busy, exclusive, unplugged, a format the device will not take), and a host that shows
     * the user "microphone unavailable" needs the exception, not a launched coroutine that swallowed
     * it. `RecorderViewModel` awaits this and puts the message on screen; that banner is the only
     * reason the Windows line-allocation failure was diagnosable at all.
     *
     * Callers on a UI dispatcher should keep this off the main thread: opening a line is hardware work,
     * and this additionally waits for any pending release to land first (see [AudioRecorder.start]).
     */
    suspend fun startAndJoin(spec: AudioSpec) {
        factory.startRecording(id, spec)
    }

    override fun pause() {
        // Recording pause is effectively stopping the hardware stream
        // but keeping the connection 'active' in the factory.
        if (factory.isActiveRecorder(id)) {
            factory.getRecorderWorker().pause()
        }
    }

    /**
     * Releases the microphone, and does not need this connection's [scope] to survive the call.
     *
     * This is the teardown path — every host calls it from `onCleared`/`onDispose` — so it deliberately
     * does NOT use [scope]: that scope is already cancelled by then, and a `scope.launch` here simply
     * never ran, which is how the capture line came to be left open. [AudioRecorderConnectionFactory.
     * releaseRecording] owns the release on a scope that outlives any screen.
     *
     * Use [stopAndJoin] when the caller needs the microphone to be closed before it does the next
     * thing — finalising a clip, for instance.
     */
    override fun stop() {
        factory.releaseRecording(id)
    }

    /**
     * [stop], awaited — for a caller that has to know the microphone is closed before continuing.
     *
     * `InsertRecorder.finish()` is the case: it closes the clip's WAV writer and then measures the
     * file, so packets still arriving from a mic that has not stopped yet would land after the header
     * was finalised.
     */
    suspend fun stopAndJoin() {
        factory.stopRecording(id)
    }

    /**
     * The recorder's audio, which is the SHARED worker stream — not this connection's alone.
     *
     * Unlike playback, which stamps each event with its owner and filters per connection (see
     * `AudioPlayerConnectionFactory.eventsFor` and the damage it documents), recording has the
     * arbitration half of that design and not the isolation half: every connection sees every packet.
     * That is safe only because one recorder is active at a time and each host gates its own collectors
     * on its own state. A second *simultaneously capturing* consumer would need ownership stamped at
     * emission first — do not add one on the assumption that the id already isolates the stream.
     */
    override fun getAudioStream(): Flow<ByteArray> {
        return factory.getRecorderWorker().audioStream
    }

    fun isRecording(): Boolean {
        return factory.isActiveRecorder(id) && factory.getRecorderWorker().isRecording()
    }
}