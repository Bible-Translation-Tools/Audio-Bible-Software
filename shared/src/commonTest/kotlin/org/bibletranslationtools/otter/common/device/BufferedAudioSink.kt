package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A mock sink with a real hardware buffer, and the only one that can tell you **which frame of the take
 * is currently audible**.
 *
 * [PacedAudioSink] has no queue: what it is handed is instantly "played", so its play cursor and its
 * write cursor are the same number. Real hardware queues hundreds of milliseconds — the JvmAudioSink
 * comment measures ~400 ms on macOS — and every interesting position bug lives in that gap:
 *
 *  - `framePosition` is `writtenFrames - queuedFrames`, exactly as [JvmAudioSink] derives it, so the
 *    reported position is the AUDIBLE one and lags the writer by the queue depth.
 *  - `flush()` **discards** what is queued. Frames the player already read from the file and wrote here
 *    were never heard. Whether the take resumes from the audible frame or from the write cursor decides
 *    whether that audio is replayed or silently skipped, and nothing without a queue can test it.
 *  - `isRunning` means "framePosition is the audible position", so it tracks started/stopped and NOT
 *    whether the queue happens to be empty. JvmAudioSink used to forward JavaSound's own answer to that
 *    second question and it cost two bugs; see [JvmAudioSink.isRunning].
 *
 * To make "what is audible" exact rather than inferred, this sink reads the content frame index that
 * [IndexedAudioFileReader] stamps into the head of every buffer, keeps the queue as segments of
 * (content start, frame count), and drains it in real time. [audibleContentFrame] is then the take frame
 * a listener is hearing at this instant — the ground truth to compare a drawn playhead against.
 */
class BufferedAudioSink(
    private val bufferFrames: Int = 44_100 * 4 / 10, // ~400ms, matching the measured macOS depth
    private val sampleRate: Int = 44_100,
    /**
     * Where this sink gets "now" from. Injectable so a test can drive drainage from the SAME clock it
     * feeds [org.bibletranslationtools.shared.audio.engine.PlaybackDisplayPosition.onFrame], which is what
     * turns "is the drawn playhead where the sound is" from a real-time race into an exact comparison.
     */
    private val nanoTime: () -> Long = System::nanoTime
) : ObservableAudioSink {

    private class Segment(val contentStart: Long, val frames: Long, var consumed: Long = 0)

    private val _writes = MutableStateFlow(0)
    override val writes: StateFlow<Int> = _writes

    private val _framesWritten = MutableStateFlow(0L)
    override val framesWritten: StateFlow<Long> = _framesWritten

    private val _calls = MutableStateFlow<List<String>>(emptyList())
    override val calls: StateFlow<List<String>> = _calls

    private val _openEntered = MutableStateFlow(false)
    override val openEntered: StateFlow<Boolean> = _openEntered

    private val lock = Any()
    private val queue = ArrayDeque<Segment>()

    private var written = 0L
    private var queued = 0L
    private var started = false
    private var lastAdvanceNanos = nanoTime()

    @Volatile
    private var audible = 0L

    @Volatile
    private var playedThrough = -1L

    @Volatile
    private var skipped = 0L

    /** One stretch of the take that was never heard: playback jumped from [fromFrame] to [toFrame]. */
    class Skip(val fromFrame: Long, val toFrame: Long) {
        val frames: Long get() = toFrame - fromFrame
        override fun toString() = "gap $fromFrame..$toFrame (${frames}f)"
    }

    /** A stretch handed to the hardware: [contentStart] for [frames] frames. */
    class Written(val contentStart: Long, val frames: Long) {
        override fun toString() = "$contentStart+$frames"
    }

    private val _skips = mutableListOf<Skip>()
    private val _written = mutableListOf<Written>()
    private val _discarded = mutableListOf<Written>()

    /**
     * Where audio went missing, in take frames. Together with [writtenSegments] and [discardedSegments]
     * this says WHICH of the two possible causes it was: a gap already present in the write order means
     * the reader jumped (a seek or a bad position anchor), while contiguous writes with a gap in playback
     * means a flush threw a segment away between writing it and hearing it.
     */
    val skips: List<Skip> get() = synchronized(lock) { _skips.toList() }

    /** Every stretch handed over, in write order. A gap here is the writer's fault, not the queue's. */
    val writtenSegments: List<Written> get() = synchronized(lock) { _written.toList() }

    /** Every stretch discarded unheard by a [flush]. */
    val discardedSegments: List<Written> get() = synchronized(lock) { _discarded.toList() }

    /** Human-readable summary for a failure message. */
    fun diagnose(): String = synchronized(lock) {
        val writeGaps = _written.zipWithNext()
            .filter { (a, b) -> b.contentStart != a.contentStart + a.frames }
            .map { (a, b) -> "${a.contentStart + a.frames}->${b.contentStart}" }
        buildString {
            append("skips=").append(_skips.take(6))
            append(" writeGaps=").append(writeGaps.take(6))
            append(" discarded=").append(_discarded.take(6))
            append(" (skips=${_skips.size} writes=${_written.size} discards=${_discarded.size})")
        }
    }

    /**
     * Total take frames that were never heard: content the playhead jumped over between one audible
     * stretch and the next. A pause that resumes from further ahead than it actually reached leaves audio
     * behind here, however tidy the reported position looks.
     */
    val skippedContentFrames: Long get() { advance(); return skipped }


    @Volatile
    private var openHeld = false

    @Volatile
    private var bytesPerFrame = AudioSpec().bytesPerFrame

    /**
     * The take frame currently being heard. Only meaningful with [IndexedAudioFileReader] supplying the
     * audio, since that is what stamps the content index this decodes.
     */
    val audibleContentFrame: Long get() { advance(); return audible }

    override val framePosition: Long
        get() {
            advance()
            return synchronized(lock) { (written - queued).coerceAtLeast(0L) }
        }

    override val isRunning: Boolean
        get() {
            advance()
            // Started, regardless of whether the queue happens to be empty this instant. A starved line
            // still knows exactly what has been heard, and [AudioSink.isRunning] means "framePosition is
            // the audible position", not "the mixer is presenting data right now" — see JvmAudioSink,
            // where conflating the two produced a playhead that jumped backwards and then, once that was
            // patched over, one that fell seconds behind.
            return synchronized(lock) { started }
        }

    override fun open(spec: AudioSpec) {
        _openEntered.value = true
        awaitRelease { openHeld }
        bytesPerFrame = spec.bytesPerFrame.coerceAtLeast(1)
        synchronized(lock) {
            queue.clear()
            queued = 0
            written = 0
        }
        record("open")
    }

    override fun start() {
        advance()
        synchronized(lock) { started = true }
        record("start")
    }

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        val frames = (size / bytesPerFrame).toLong()
        val contentStart = IndexedAudioFileReader.decodeContentStart(data, offset)

        // Block until the buffer has room — the backpressure that paces real playback — but only while
        // the line is actually draining. `SourceDataLine.write` "returns early" if the line is stopped,
        // flushed or closed before the requested amount has been written, and a stopped line never frees
        // space, so blocking there would hang forever. Returning short is what real hardware does, and it
        // is the caller's job to notice.
        while (true) {
            advance()
            val state = synchronized(lock) {
                when {
                    queued + frames <= bufferFrames -> "room"
                    !started -> "stopped"
                    else -> "full"
                }
            }
            if (state == "room") break
            if (state == "stopped") return 0
            Thread.sleep(1)
        }

        synchronized(lock) {
            queue.addLast(Segment(contentStart, frames))
            queued += frames
            written += frames
            if (_written.size < DIAGNOSTIC_CAP) _written += Written(contentStart, frames)
        }
        _framesWritten.update { it + frames }
        _writes.update { it + 1 }
        return size
    }

    override fun stop() {
        advance()
        // JavaSound's stop() pauses: queued data is retained, and only flush() throws it away.
        synchronized(lock) { started = false }
        record("stop")
    }

    override fun drain() {
        // Blocks until everything queued has actually been heard.
        while (true) {
            advance()
            val empty = synchronized(lock) { queued <= 0L || !started }
            if (empty) break
            Thread.sleep(1)
        }
        record("drain")
    }

    override fun flush() {
        advance()
        synchronized(lock) {
            // Everything still queued is DISCARDED — never heard, however recently written.
            queue.forEach { seg ->
                val unheard = seg.frames - seg.consumed
                if (unheard > 0 && _discarded.size < DIAGNOSTIC_CAP) {
                    _discarded += Written(seg.contentStart + seg.consumed, unheard)
                }
            }
            queue.clear()
            queued = 0
            written = 0
        }
        record("flush")
    }

    override fun close() {
        synchronized(lock) {
            started = false
            queue.clear()
            queued = 0
        }
        record("close")
    }


    override fun holdOpen() {
        openHeld = true
    }

    override fun releaseOpen() {
        openHeld = false
    }

    /** Drains the queue by however much wall time has passed, tracking which content frame that lands on. */
    private fun advance() {
        synchronized(lock) {
            val now = nanoTime()
            val elapsedNanos = now - lastAdvanceNanos
            lastAdvanceNanos = now
            if (!started || elapsedNanos <= 0) return
            var toDrain = elapsedNanos * sampleRate / 1_000_000_000L
            while (toDrain > 0 && queue.isNotEmpty()) {
                val head = queue.first()
                if (head.consumed == 0L && playedThrough >= 0L && head.contentStart > playedThrough) {
                    if (_skips.size < DIAGNOSTIC_CAP) _skips += Skip(playedThrough, head.contentStart)
                    // This segment starts past where the last audible one ended: the content in between
                    // was never played to anyone. Overlap (a segment starting earlier) is the benign
                    // case — audio replayed after a flush — and is not counted.
                    skipped += head.contentStart - playedThrough
                }
                val remaining = head.frames - head.consumed
                val step = minOf(toDrain, remaining)
                head.consumed += step
                queued -= step
                toDrain -= step
                audible = head.contentStart + head.consumed
                playedThrough = maxOf(playedThrough, audible)
                if (head.consumed >= head.frames) queue.removeFirst()
            }
        }
    }

    private fun awaitRelease(held: () -> Boolean) {
        // Real time deliberately: this is a test gate, not part of the audio clock.
        val deadline = System.nanoTime() + HELD_CALL_CAP_MILLIS * 1_000_000
        while (held() && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
    }

    private fun record(call: String) = _calls.update { it + call }

    private companion object {
        const val HELD_CALL_CAP_MILLIS = 5_000L

        /** Diagnostic lists are bounded: a long take writes thousands of segments. */
        const val DIAGNOSTIC_CAP = 400
    }
}
