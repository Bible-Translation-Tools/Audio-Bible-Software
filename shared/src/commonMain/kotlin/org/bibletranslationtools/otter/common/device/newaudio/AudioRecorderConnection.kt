package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class AudioRecorderConnection(
    private val id: Int,
    private val factory: AudioRecorderConnectionFactory,
    private val scope: CoroutineScope
) : IAudioRecorder {

    override fun start(spec: AudioSpec) {
        scope.launch {
            factory.startRecording(id, spec)
        }
    }

    override fun pause() {
        // Recording pause is effectively stopping the hardware stream
        // but keeping the connection 'active' in the factory.
        if (factory.isActiveRecorder(id)) {
            factory.getRecorderWorker().pause()
        }
    }

    override fun stop() {
        scope.launch {
            factory.stopRecording(id)
        }
    }

    override fun getAudioStream(): Flow<ByteArray> {
        return factory.getRecorderWorker().audioStream
    }

    fun isRecording(): Boolean {
        return factory.isActiveRecorder(id) && factory.getRecorderWorker().isRecording()
    }
}