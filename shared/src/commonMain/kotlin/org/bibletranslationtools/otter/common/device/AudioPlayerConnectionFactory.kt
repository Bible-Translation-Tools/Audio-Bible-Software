package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
    @Volatile
    private var activeConnectionId: Int? = null
    private var activeReader: AudioFileReader? = null
    private var activeStartPosition: Long = 0
    private val mutex = Mutex()

    fun isActiveConnection(id: Int): Boolean = activeConnectionId == id

    suspend fun connect(connectionId: Int, reader: AudioFileReader, position: Long) = mutex.withLock {
        val needsReconnect = activeConnectionId != connectionId ||
            activeReader !== reader ||
            activeStartPosition != position

        if (needsReconnect) {
            // Stopping the outgoing connection is still the outgoing connection's event: its host has
            // to learn that its playback ended, and this Pause is the only thing that tells it.
            player.pause()
            // Everything from here on — the Load, and the Play/Complete of the playback about to
            // start — belongs to the connection taking over.
            player.setEventOwner(connectionId)
            player.load(reader)
            player.seek(position)
            activeConnectionId = connectionId
            activeReader = reader
            activeStartPosition = position
        }
    }

    fun getPlayerWorker() = player

    /**
     * The transport events belonging to one connection.
     *
     * The worker is shared, so its raw stream carries every connection's events. A host that reads the
     * raw stream acts on transitions it did not cause: the damaging one is another connection's
     * `Complete`, which makes the host park its display at the end of a take that is still playing —
     * and then, when the user presses play, the display rewinds to zero because it looks finished.
     * Filtering here is by the owner stamped at emission, not by whoever holds the hardware when the
     * collector happens to run.
     */
    fun eventsFor(connectionId: Int): Flow<AudioPlayerEvent> =
        player.ownedEvents
            .filter { it.owner == connectionId }
            .map { it.event }

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
