package org.bibletranslationtools.otter.common.device

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine

class JvmAudioSink(
    /**
     * How much audio the hardware line should buffer. This is the most consequential number in the
     * playback path, and it used to be left unset.
     *
     * `line.open(format)` lets the mixer pick, and measured on this machine that default is **500ms**,
     * for every format including 48k/24/stereo. Everything downstream inherits it: the reported position
     * can be wrong by up to a bufferful, a pause discards up to a bufferful, and the display has to be
     * corrected by that much. Asking for a size instead is honoured exactly — measured 20ms asked /
     * 20.0ms given, likewise 50, 100 and 200 — so this layer's latency is a number we choose, not one we
     * inherit.
     *
     * Measured on real hardware, once a pause stopped discarding its queue (steady-state error / six-cycle
     * pause-resume shortfall):
     *
     * ```
     * 500ms   0ms   194ms      <- the mixer's own default
     * 100ms   0ms   229ms
     *  50ms   0ms   219ms
     *  20ms -23ms  1659ms      <- below the floor
     * ```
     *
     * 20ms fails for a concrete reason: the player writes 1024 frames at a time, which is 23ms at 44.1k, so
     * a 20ms buffer cannot hold even one write. **The floor is twice the write chunk** — one chunk in
     * flight and one draining — which is 46ms here. Going below that means writing smaller chunks, not
     * asking for a smaller buffer.
     *
     * Those numbers all predate the resume rework and were taken while a pause still flushed and a resume
     * still reloaded; the shortfall column is measuring that dead time, not the buffer. What the buffer
     * governs now is how long a pause waits for the in-flight blocking `write()` to return, which is
     * bounded by the free space it has to wait for.
     */
    private val bufferMillis: Int = DEFAULT_BUFFER_MILLIS,
    private val lineProvider: () -> SourceDataLine?
) : AudioSink {

    private var currentLine: SourceDataLine? = null

    // Frames written into the line since the last flush (AudioBufferPlayer's contract:
    // framePosition restarts at 0 after a flush, like Android's AudioTrack).
    @Volatile
    private var writtenFrames: Long = 0
    @Volatile
    private var frameSizeBytes: Int = 2
    @Volatile
    private var sampleRate: Int = 44_100

    // Whether this sink has been started and not yet stopped. Deliberately NOT `line.isRunning`.
    @Volatile
    private var started: Boolean = false

    // Position accounting: frames credited as played before the current run, plus when that run began.
    // Together with the wall clock these give the audible position — see [framePosition].
    @Volatile
    private var playedBeforeRun: Long = 0
    @Volatile
    private var runStartNanos: Long = 0

    /** Frames the line was asked to buffer — the bound on how stale [framePosition] can be. */
    val bufferFrames: Int get() = sampleRate * bufferMillis / 1_000

    /**
     * Whether [framePosition] is the audible position — the only thing the two consumers of this flag are
     * asking. [AudioBufferPlayer.getLocationInFrames] uses it to choose between this position and the
     * write cursor, and `isPositionReliable` hands it to the display clock.
     *
     * `SourceDataLine.isRunning()` answers a different question and cannot be used here: by its contract a
     * line runs from when data is first presented "until presentation ceases", and presentation ceases
     * whenever the buffer empties. The playback loop reads from disk and writes to the line on one thread,
     * so it empties often — and the position remains valid throughout.
     *
     * A stopped line is the genuinely different case, and still reports false: the player then reports the
     * write cursor, which is ahead of anything audible.
     */
    override val isRunning: Boolean
        get() = started && (currentLine != null)

    /**
     * The AUDIBLE position, derived from elapsed time and clamped to what has been handed over.
     *
     * Neither of JavaSound's own answers survives contact with real devices. `longFramePosition` tracks
     * frames consumed into the native buffer — write-side, ahead of the speaker by the whole buffer depth
     * — and never resets on flush. `writtenFrames - (bufferSize - available())` looks better but only
     * holds while `available()` tracks the drain; on a device with a deep buffer that misreports its free
     * space the two terms cancel, and this read **0 for three seconds of audible playback** before leaping
     * to catch up. That was measured in the field.
     *
     * Elapsed time needs no cooperation from the device: audio plays at one frame per 1/sampleRate of real
     * time or it is not playing at all. The clamp to [writtenFrames] keeps it honest in the other
     * direction — it can never claim to have played audio the hardware was never given, which is the
     * direction that silently swallows content on resume.
     *
     * Residual error is the device's own output latency, bounded by [bufferMillis].
     */
    override val framePosition: Long
        get() {
            if (currentLine == null) return playedBeforeRun
            return audibleFrames()
        }

    /**
     * Elapsed-time position, with starved time discarded rather than banked.
     *
     * Elapsed time is only audio while there is audio queued to play. A started line with an empty queue
     * emits silence, and the two places that happens are not corner cases: every resume begins with the
     * queue empty and the writer a buffer behind, and every underrun mid-playback does the same. Clamping
     * the answer on the way out is not enough — the deficit survives in [playedBeforeRun] and gets repaid
     * the instant the next write raises the ceiling, which reads as the position running ahead of the
     * sound. Measured before this: ~45ms of over-report per pause/resume cycle, compounding.
     *
     * So when the clock outruns the writer, the clock is wrong: re-anchor at the last frame the line
     * actually had and start counting again from now. Time spent starved is simply gone, which is what it
     * was.
     *
     * Mutating from a read is deliberate. This is the only place that can notice the starvation, and both
     * callers — the position getter and [write] — must see the corrected value rather than a stale one.
     * Concurrent calls race only to write the same two values, and both fields are volatile.
     */
    private fun audibleFrames(): Long {
        if (!started) return playedBeforeRun
        val now = System.nanoTime()
        val elapsedNanos = now - runStartNanos
        val played = if (elapsedNanos > 0) {
            playedBeforeRun + elapsedNanos * sampleRate / 1_000_000_000L
        } else {
            playedBeforeRun
        }
        if (played < writtenFrames) return played
        playedBeforeRun = writtenFrames
        runStartNanos = now
        return writtenFrames
    }

    private fun bufferBytesFor(spec: AudioSpec): Int {
        val frameBytes = ((spec.bitDepth / 8) * spec.channels).coerceAtLeast(1)
        val frames = spec.sampleRate.toLong() * bufferMillis / 1_000L
        return (frames * frameBytes).coerceAtLeast(frameBytes.toLong()).toInt()
    }

    override fun open(spec: AudioSpec) {
        val line = lineProvider() ?: throw IllegalStateException("No SourceDataLine available")

        val format = AudioFormat(
            spec.sampleRate.toFloat(),
            spec.bitDepth,
            spec.channels,
            true, // signed
            spec.isBigEndian
        )

        // Reuse the line when the format already matches. connect() calls load() on every resume, and
        // load() calls open() — so a close/open here is a hardware cycle per resume, tens to hundreds of
        // milliseconds during which nothing is audible while the display clock is already advancing.
        // Measured in the field while spam-clicking, that accumulated 0.76s of the drawn playhead running
        // ahead of the sound. Nothing is lost by reusing: stop() + flush() discards the queued audio,
        // which is all load() needs, and the position accounting below resets either way.
        if (line.isOpen && line.format.matches(format)) {
            // Flush without stopping. Discarding the previous take's queue is the whole job here, and
            // `flush()` does that on a running line in 0ms (measured), whereas stopping means the next
            // `start()` pays 230-310ms before anything is audible. Since load() runs on the way into every
            // play, that restart was showing up as the startup latency of the first play after a load.
            line.flush()
        } else {
            if (line.isOpen) {
                line.stop()
                line.flush()
                line.close()
            }
            line.open(format, bufferBytesFor(spec))
        }

        currentLine = line
        // A reopened line is not started until start() says so.
        started = false
        frameSizeBytes = ((spec.bitDepth / 8) * spec.channels).coerceAtLeast(1)
        sampleRate = spec.sampleRate.coerceAtLeast(1)
        writtenFrames = 0
        playedBeforeRun = 0
    }

    override fun start() {
        currentLine?.start()
        // Bank what the previous run played before restarting the clock, so a stop/start pair resumes
        // rather than replaying: a stop without a flush keeps its queued audio.
        playedBeforeRun = audibleFrames()
        runStartNanos = System.nanoTime()
        started = true
    }

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        // Settle the clock BEFORE these frames raise the ceiling. If the line was starved — which it is at
        // the start of every resume, with the writer a buffer behind — this is the moment that stretch of
        // silence is written off, so the audio about to be queued is counted from now rather than from
        // whenever start() happened to be called.
        if (started) audibleFrames()
        val written = currentLine?.write(data, offset, size) ?: 0
        writtenFrames += written / frameSizeBytes
        return written
    }

    override fun stop() {
        // Freeze the position first: a stopped line plays nothing, so it must not creep with the clock.
        playedBeforeRun = audibleFrames()
        started = false
        // stop(), not stop()+flush(): SourceDataLine.stop halts playback and retains the queued audio,
        // which is exactly the AudioSink contract and exactly what a pause needs. A start() later picks up
        // where this left off, in both the sound and the arithmetic above.
        currentLine?.stop()
    }

    override fun drain() {
        currentLine?.drain()
    }

    override fun flush() {
        currentLine?.flush()
        // Queued audio is discarded; position accounting restarts (the player
        // re-anchors sessionStartFrame on the next play/seek).
        writtenFrames = 0
        playedBeforeRun = 0
        runStartNanos = System.nanoTime()
    }

    override fun close() {
        currentLine?.close()
        currentLine = null
        started = false
        playedBeforeRun = 0
    }

    companion object {
        /**
         * Only a fallback for a sink built without one — the value the app actually runs on comes from
         * [AudioConfig.outputBufferMillis] via `JvmAudioHardwareProvider`, which is where it can be
         * changed and where the floor is enforced.
         *
         * The reason to be at 50ms rather than the mixer's own 500ms is that the buffer bounds two
         * different waits, and both are felt as the transport being unresponsive. It bounds how far the
         * reported position can lag the speaker, and — since a pause has to join a playback loop that may
         * be blocked in `write()` — it bounds how long a pause takes. Twelve toggles 20ms apart used to
         * take startup from 38ms to 467ms; most of that was reload work, but the rest was queued audio
         * that had to go somewhere before a pause could return.
         *
         * It could only be lowered once a pause stopped flushing and a resume stopped reloading, because
         * until then a deep buffer was the only thing masking ~250ms of dead time per resume — shrink it
         * first and the dead time becomes silence. Both are now done, which is what makes this safe. The
         * gates are `RealAudioPositionTest.theReportedPositionDoesNotFallBehindAcrossPauseAndResume` and
         * `rapidTogglingStillEndsWithAudioPlaying`.
         */
        const val DEFAULT_BUFFER_MILLIS = AudioConfig.DEFAULT_OUTPUT_BUFFER_MILLIS
    }
}
