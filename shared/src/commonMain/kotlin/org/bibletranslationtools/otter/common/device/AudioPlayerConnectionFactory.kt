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
     * Hands the worker a different output device. Suspending, to coordinate with the worker's mutex.
     *
     * A new sink is **not yet open**: [AudioHardwareProvider.createSink] builds the handle, and the
     * format is only known at `load()`, because it is the take's rather than the device's. So the one
     * thing this must guarantee is that nothing plays until something re-opens it — which is what
     * clearing the connection state does. The next `play()` finds itself no longer the active
     * connection, goes through [connect], and that opens the new device with the current take's format.
     *
     * It used to call `player.play()` here if the old sink was running, which was wrong in a way that
     * got worse rather than better: the new sink has no line yet, so `start()` is a no-op, `write()`
     * returns 0, and the playback loop rewinds the reader and writes nothing round and round. It also
     * left `activeConnectionId` pointing at a connection that believed the hardware was still its own —
     * so once resuming stopped going through `connect()` (it now skips it when only transport state
     * changed), a later play would have skipped the re-open too.
     *
     * Playback therefore STOPS on a device change rather than following the audio across. That is a
     * deliberate downgrade of an ability that did not work: resuming means re-opening a device and
     * re-seeking a take, which only a connection can do, and this has no way to ask one. Pressing play
     * again is correct and immediate.
     */
    suspend fun updateHardwareSink(newSink: AudioSink) = mutex.withLock {
        // Stop the writer before the hardware goes away, so it is not mid-`write()` into a closed line.
        player.pause()

        sink.stop()
        sink.close()

        this.sink = newSink
        player.setSink(newSink)

        // Nothing is connected to the new device yet. Saying so is what forces the re-open.
        activeConnectionId = null
        activeReader = null
        activeStartPosition = 0
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
