package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.flow.Flow

interface IAudioRecorder {
    // We pass the spec here so the recorder knows if it's 16/24-bit
    fun start(spec: AudioSpec = AudioSpec())
    fun pause()
    fun stop()
    // Replaces RxJava Observable with Coroutine Flow
    fun getAudioStream(): Flow<ByteArray>
}