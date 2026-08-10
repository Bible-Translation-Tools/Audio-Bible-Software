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
 * 3. **Lifecycle calls are not instantaneous.** [holdOpen] makes [open] block until released, which
 *    turns a microsecond-wide race into something assertable: it pins the window where `load()` holds
 *    the player's mutex while the hardware opens, which is when a caller that takes that mutex to read
 *    the position gets blocked. (There was a `holdStop` too, pinning the window where a finished job
 *    had emitted `Complete` but not finished its `finally`. That teardown no longer touches the
 *    hardware, so there is nothing left to hold.)
 *
 * Every observable is a [StateFlow] so tests can await a condition instead of guessing a delay.
 */
class PacedAudioSink(private val millisPerWrite: Long = 1L) : ObservableAudioSink {

    private val _writes = MutableStateFlow(0)

    /** Number of completed [write] calls — one per buffer the playback loop has pushed. */
    override val writes: StateFlow<Int> = _writes

    private val _framesWritten = MutableStateFlow(0L)

    /** Total frames pushed across every play session, never reset. */
    override val framesWritten: StateFlow<Long> = _framesWritten

    private val _calls = MutableStateFlow<List<String>>(emptyList())

    /**
     * Log of lifecycle calls (`open`, `start`, `stop`, `drain`, `flush`, `close`) in order.
     * Writes are excluded — there are hundreds of them, and [writes] already counts them.
     */
    override val calls: StateFlow<List<String>> = _calls

    @Volatile
    override var framePosition: Long = 0L
        private set

    @Volatile
    override var isRunning: Boolean = false
        private set

    @Volatile
    private var bytesPerFrame: Int = AudioSpec().bytesPerFrame


    @Volatile
    private var openHeld: Boolean = false

    private val _openEntered = MutableStateFlow(false)

    /** True once [open] has been entered — so a test can know the player's mutex is held. */
    override val openEntered: StateFlow<Boolean> = _openEntered

    override fun open(spec: AudioSpec) {
        _openEntered.value = true
        // Real hardware is slow to open: a SourceDataLine takes tens of milliseconds, sometimes far
        // more. AudioBufferPlayer.load() holds its mutex across this call, so anything that takes that
        // mutex waits for the hardware.
        awaitRelease { openHeld }
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


    /** Makes the next [open] block until [releaseOpen] is called. */
    override fun holdOpen() {
        openHeld = true
    }

    override fun releaseOpen() {
        openHeld = false
    }

    /**
     * Blocks while [held], with a hard cap so a test that forgets to release fails on its own
     * assertions rather than hanging the whole suite.
     */
    private fun awaitRelease(held: () -> Boolean) {
        val deadline = System.nanoTime() + HELD_CALL_CAP_MILLIS * 1_000_000
        while (held() && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
    }

    private fun record(call: String) = _calls.update { it + call }

    private companion object {
        const val HELD_CALL_CAP_MILLIS = 5_000L
    }
}
