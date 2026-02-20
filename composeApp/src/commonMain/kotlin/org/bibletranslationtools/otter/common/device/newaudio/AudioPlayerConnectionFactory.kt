package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AudioPlayerConnectionFactory private constructor(
    private var sink: AudioSink,
    private val processor: AudioProcessor,
    private val player: AudioBufferPlayer
) {
    constructor(sink: AudioSink, processor: AudioProcessor) : this(
        sink = sink,
        processor = processor,
        player = AudioBufferPlayer(sink, processor)
    )

    // Tracks which virtual connection is "active" in the hardware
    private var activeConnectionId: Int? = null
    private val mutex = Mutex()

    fun isActiveConnection(id: Int): Boolean = activeConnectionId == id

    suspend fun connect(connectionId: Int, reader: AudioFileReader, position: Long) = mutex.withLock {
        if (activeConnectionId != connectionId) {
            player.pause()
            player.load(reader)
            player.seek(position)
            activeConnectionId = connectionId
        }
    }

    fun getPlayerWorker() = player

    /**
     * Updated to be suspending to coordinate with the worker's Mutex.
     */
    suspend fun updateHardwareSink(newSink: AudioSink) = mutex.withLock {
        val wasRunning = player.isSinkRunning

        // 1. Clean up old hardware
        sink.stop()
        sink.close()

        // 2. Update local reference
        this.sink = newSink

        // 3. Update the worker safely through its Mutex
        player.setSink(newSink)

        // 4. Resume if it was playing
        if (wasRunning) {
            player.play()
        }
    }

    companion object {
        fun createForScope(
            sink: AudioSink,
            processor: AudioProcessor,
            scope: CoroutineScope
        ): AudioPlayerConnectionFactory {
            return AudioPlayerConnectionFactory(
                sink = sink,
                processor = processor,
                player = AudioBufferPlayer(sink, processor, scope)
            )
        }
    }
}
