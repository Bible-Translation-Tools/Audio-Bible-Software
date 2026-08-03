package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * An [AudioSink] that models the three hardware behaviours the transport actually depends on.
 * [MockAudioSink] deliberately models none of them; it exists for arithmetic checks.
 *
 * 1. **Writes take real time.** A real sink blocks in [write] once its hardware buffer is full, and
 *    that backpressure is the only thing that makes a take take time. A sink that returns instantly
 *    lets a whole take drain inside one scheduler pump, so pause/seek/second-play can only ever be
 *    tested *before* or *after* playback — never during it, which is where the transport bugs live.
 *
 * 2. **[flush] clears the frame counter; [stop] does not.** Both `AudioTrack.playbackHeadPosition`
 *    and `SourceDataLine.framePosition` survive a stop and are only reset by a flush. This is what
 *    [AudioBufferPlayer]'s `sessionStartFrame` / `sinkFrameBaseline` pair exists to absorb, so a
 *    mock that never resets (or always resets) silently skips half of that logic.
 *
 * 3. **Teardown is not instantaneous.** [holdStop] makes [stop] block until [releaseStop], which
 *    pins the window where [AudioBufferPlayer]'s playback job has already emitted `Complete` but has
 *    not yet finished its `finally` block — the window in which `play()` is silently swallowed
 *    because `playbackJob?.isActive` is still true.
 *
 * Every observable is a [StateFlow] so tests can await a condition instead of guessing a delay.
 */
class PacedAudioSink(private val millisPerWrite: Long = 1L) : AudioSink {

    private val _writes = MutableStateFlow(0)

    /** Number of completed [write] calls — one per buffer the playback loop has pushed. */
    val writes: StateFlow<Int> = _writes

    private val _framesWritten = MutableStateFlow(0L)

    /** Total frames pushed across every play session, never reset. */
    val framesWritten: StateFlow<Long> = _framesWritten

    private val _calls = MutableStateFlow<List<String>>(emptyList())

    /**
     * Log of lifecycle calls (`open`, `start`, `stop`, `drain`, `flush`, `close`) in order.
     * Writes are excluded — there are hundreds of them, and [writes] already counts them.
     */
    val calls: StateFlow<List<String>> = _calls

    @Volatile
    override var framePosition: Long = 0L
        private set

    @Volatile
    override var isRunning: Boolean = false
        private set

    @Volatile
    private var bytesPerFrame: Int = AudioSpec().bytesPerFrame

    @Volatile
    private var stopHeld: Boolean = false

    override fun open(spec: AudioSpec) {
        bytesPerFrame = spec.bytesPerFrame.coerceAtLeast(1)
        record("open")
    }

    override fun start() {
        isRunning = true
        record("start")
    }

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        if (millisPerWrite > 0) Thread.sleep(millisPerWrite)
        val frames = size / bytesPerFrame
        framePosition += frames
        _framesWritten.update { it + frames }
        _writes.update { it + 1 }
        return size
    }

    override fun stop() {
        awaitStopRelease()
        // Deliberately does NOT reset framePosition — see the class comment.
        isRunning = false
        record("stop")
    }

    override fun drain() = record("drain")

    override fun flush() {
        framePosition = 0
        record("flush")
    }

    override fun close() {
        isRunning = false
        record("close")
    }

    /** Makes the next [stop] block until [releaseStop] is called. */
    fun holdStop() {
        stopHeld = true
    }

    fun releaseStop() {
        stopHeld = false
    }

    /**
     * Blocks while [stopHeld], with a hard cap so a test that forgets [releaseStop] fails on its own
     * assertions rather than hanging the whole suite.
     */
    private fun awaitStopRelease() {
        val deadline = System.nanoTime() + HELD_STOP_CAP_MILLIS * 1_000_000
        while (stopHeld && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
    }

    private fun record(call: String) = _calls.update { it + call }

    private companion object {
        const val HELD_STOP_CAP_MILLIS = 5_000L
    }
}
