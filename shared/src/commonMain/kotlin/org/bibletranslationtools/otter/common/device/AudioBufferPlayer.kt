package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class AudioBufferPlayer(
    sink: AudioSink,
    val processor: AudioProcessor,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _events = MutableSharedFlow<AudioPlayerEvent.Owned>(extraBufferCapacity = 10)

    /**
     * Every event from the shared worker, tagged with the connection it belongs to. Consumers should
     * take [AudioPlayerConnectionFactory.eventsFor] instead — see [AudioPlayerEvent.Owned].
     */
    internal val ownedEvents = _events.asSharedFlow()

    /** Every event, whoever it belongs to. */
    val events: Flow<AudioPlayerEvent> = ownedEvents.map { it.event }

    // Which connection subsequent events describe. Read at emission, never at collection.
    @Volatile
    private var eventOwner: Int? = null

    /**
     * Declares which connection the events emitted from here on belong to. Called by the factory as
     * it hands the hardware over, between stopping the outgoing connection (whose host still needs to
     * hear that Pause) and loading the incoming one's audio.
     */
    internal fun setEventOwner(connectionId: Int?) {
        eventOwner = connectionId
    }

    private suspend fun emitEvent(event: AudioPlayerEvent) {
        _events.emit(AudioPlayerEvent.Owned(eventOwner, event))
    }

    private var reader: AudioFileReader? = null
    private var playbackJob: Job? = null

    // This mutex protects both the 'sink' reference and 'reader' state
    private val mutex = Mutex()
    private var _sink: AudioSink = sink

    private var startPosition: Long = 0
    private var isPaused = false
    @Volatile
    private var lastKnownLocationInFrames: Long = 0

    // Absolute frame at which the current AudioTrack play session started.
    // The sink's framePosition (e.g. AudioTrack.playbackHeadPosition) resets to
    // 0 on every flush/stop, so to expose an absolute position we add this
    // anchor to the sink's relative position. Reset on play() and seek().
    @Volatile
    private var sessionStartFrame: Long = 0
    // The sink's framePosition at the moment this play session started. We report
    // sessionStartFrame + (sink.framePosition - sinkFrameBaseline), i.e. only the frames
    // played SINCE play() — so if the sink's counter did NOT reset to 0 on resume (it
    // still carries the prior session's frames), the position doesn't double-count the
    // anchor. Baseline is 0 when the sink reset cleanly, leaving the fresh-play path
    // unchanged.
    @Volatile
    private var sinkFrameBaseline: Long = 0

    // ── playback sessions ───────────────────────────────────────────────────────────────────
    //
    // One play-through of the reader is one session, identified by a monotonic id. Every transport
    // transition — load, play, pause, seek, release — ends the current session by bumping the id,
    // and each playback job carries the id it was launched with. A job whose id is no longer current
    // stops reading, stops publishing its position, and emits nothing.
    //
    // This exists because [events] is a single shared stream with no per-session channel, so without
    // it a job that is still unwinding can emit onto the session that replaced it. The concrete
    // failure: seek() sets isPaused = true and cancels the job, then immediately calls play(), which
    // sets isPaused back to false — so the cancelled job's drain block below saw `!isPaused`, drained,
    // and emitted a Complete for a take the user had just seeked into. Session identity is strictly
    // stronger than the isPaused check it now backs up, because nothing can reset it.
    private val currentSession = AtomicLong(FIRST_SESSION)

    // The session whose job is currently producing audio, or [NO_SESSION]. This — not
    // `playbackJob?.isActive` — is what makes a play() request redundant; see [play].
    private val producingSession = AtomicLong(NO_SESSION)

    /**
     * Id of the current playback session, bumped by every transport transition.
     *
     * Nothing needs this today: events are filtered by session at the source, so a superseded job is
     * silent rather than something consumers must screen out. It is exposed as the value a suspending
     * `play()` / `seek()` would hand back — with it in hand a consumer could tell whether a Complete
     * belongs to the playback it started, which the shared stream cannot express on its own.
     */
    val playbackSession: Long get() = currentSession.get()

    private fun isCurrent(session: Long): Boolean = currentSession.get() == session

    /** Ends the current session and returns the new id. */
    private fun endSession(): Long = currentSession.incrementAndGet()

    /** Marks [session] as no longer producing audio, unless a newer session already took over. */
    private fun endProduction(session: Long) {
        producingSession.compareAndSet(session, NO_SESSION)
    }

    /**
     * Updates the hardware sink safely.
     * If the loop is running, the next iteration will wait for this to finish.
     */
    suspend fun setSink(newSink: AudioSink) = mutex.withLock {
        _sink = newSink
    }

    val isSinkRunning: Boolean
        get() = runBlocking { mutex.withLock { _sink.isRunning } }

    /**
     * Lock-free variant for high-frequency callers (the display clock reads this per
     * display frame). While the sink is NOT running, [getLocationInFrames] falls back
     * to the WRITE cursor — ahead of the audible position — so position readings are
     * only reliable when this is true. A momentarily stale read here is harmless (the
     * clock just skips one correction); taking the mutex at 120 Hz is not.
     */
    val isPositionReliable: Boolean
        get() = _sink.isRunning

    suspend fun load(reader: AudioFileReader) = mutex.withLock {
        // A different reader invalidates anything in flight: without this a running loop would keep
        // writing from the reader we are about to release.
        endSession()
        this.reader?.release()
        this.reader = reader.apply {
            open()
            processor.configure(spec)
            _sink.open(spec)
        }
        startPosition = 0
        lastKnownLocationInFrames = 0
        emitEvent(AudioPlayerEvent.Load)
    }

    fun play() {
        // Deliberately NOT `playbackJob?.isActive`. A job stays active through its finally block, and
        // that block runs AFTER Complete has been emitted — so a caller that replays on completion
        // (auto-replay, a loop button, a pause immediately followed by a play) had its request
        // dropped with no event and no error. The right question is whether a job for the CURRENT
        // session is still producing audio; a session that has completed, been paused, or been seeked
        // away is not, however long its job takes to unwind.
        if (producingSession.get() == currentSession.get()) return
        isPaused = false

        val session = endSession()
        // Claimed here rather than inside the job, so two play() calls in quick succession cannot both
        // get past the guard above and queue two play-throughs.
        producingSession.set(session)

        val unwinding = playbackJob
        playbackJob = scope.launch {
            // Let the previous session finish tearing down before touching the hardware: its finally
            // stops the sink, which would otherwise land after this session started it and leave a
            // whole take playing against a sink that reports itself stopped (so isPositionReliable is
            // false and the display clock stops trusting the position). Every session joins its
            // predecessor, which makes teardown strictly ordered rather than a race.
            unwinding?.join()
            if (!isCurrent(session)) {
                // Superseded while waiting for the predecessor. Nothing to tear down: this session
                // never touched the hardware.
                endProduction(session)
                return@launch
            }

            try {
                emitEvent(AudioPlayerEvent.Play)
                // Anchor the play cursor: pause/seek flushed the sink, so its
                // framePosition is about to start counting from 0.
                sessionStartFrame = lastKnownLocationInFrames
                // Start sink immediately on play so transport state is observable without race.
                // Capture the sink's frame counter NOW as the session baseline: whether it reset to
                // 0 (normal) or still carries the prior session's frames (resume), our reported
                // position stays anchored at sessionStartFrame and only adds frames played since.
                mutex.withLock {
                    _sink.start()
                    sinkFrameBaseline = _sink.framePosition
                }

                while (isActive && !isPaused && isCurrent(session)) {
                    val (currentReader, currentSink) = mutex.withLock {
                        reader to _sink
                    }

                    if (currentReader == null || !currentReader.hasRemaining()) break

                    val bytesPerFrame = currentReader.spec.bytesPerFrame.coerceAtLeast(1)
                    val inputBuffer = ByteArray(processor.inputBufferSize * bytesPerFrame)

                    // WSOLA is designed to be fed a SLIDING, overlapping analysis window, not
                    // disjoint forward-only chunks: rewind by `processor.overlap` frames before each
                    // read (after the first) so this window re-reads the tail of the previous one.
                    // Mirrors the original JVM AudioBufferPlayer.play() exactly (same rewind-then-read,
                    // same `supportsTimeShifting()` gate). Without this, WSOLA's own `sampleReq`
                    // floor-clamp for tempo < 1.0 means the reader still advances a normal-speed
                    // amount per iteration — slow rates end up mistimed rather than actually slower.
                    if (processor.playbackRate != 1.0 && currentReader.supportsTimeShifting()) {
                        val bufferFrames = inputBuffer.size / bytesPerFrame
                        if (currentReader.framePosition > bufferFrames) {
                            currentReader.seek((currentReader.framePosition - processor.overlap).toLong())
                        }
                    }

                    val read = currentReader.getPcmBuffer(inputBuffer)

                    if (read > 0) {
                        // Only pay for WSOLA time-stretching when the rate actually differs from
                        // normal speed, so the default (1.0x) playback path is unaffected. Time-
                        // stretching changes the byte count (that's the point — it changes how much
                        // audio-time a buffer covers), so the write length must track the PROCESSED
                        // buffer's own size, not the pre-processing `read` count.
                        if (processor.playbackRate == 1.0) {
                            currentSink.write(inputBuffer, 0, read)
                            // A write outlives the session that started it — the sink blocks in there
                            // until the hardware buffer drains. Publishing a position afterwards would
                            // clobber the one set by the seek that superseded us.
                            if (!isCurrent(session)) break
                            val readerFrame = currentReader.framePosition.toLong()
                            if (readerFrame > 0L) {
                                lastKnownLocationInFrames = readerFrame
                            } else {
                                val framesRead = read / bytesPerFrame
                                lastKnownLocationInFrames += framesRead.toLong()
                            }
                        } else {
                            val output = processor.process(inputBuffer.copyOf(read))
                            currentSink.write(output, 0, output.size)
                            if (!isCurrent(session)) break
                            // The reader's raw position no longer tracks true progress once we're
                            // rewinding it for the sliding window, so derive position from what the
                            // sink has actually played, scaled by the rate (JVM:
                            // `player.framePosition * playbackRate` in getLocationInFrames).
                            lastKnownLocationInFrames = sessionStartFrame +
                                ((currentSink.framePosition - sinkFrameBaseline).coerceAtLeast(0L) * processor.playbackRate).toLong()
                        }
                    }
                }

                // The session check is what keeps a superseded job out of here entirely: it must not
                // drain a sink the next session is about to use, and it must not report a completion
                // for a playback that is no longer the one happening.
                if (!isPaused && isCurrent(session)) {
                    val sinkToDrain = mutex.withLock {
                        if (reader?.hasRemaining() == false) _sink else null
                    }
                    if (sinkToDrain != null) {
                        sinkToDrain.drain()
                        val completedFrames = mutex.withLock { reader?.totalFrames?.toLong() }
                        if (completedFrames != null && completedFrames >= 0L) {
                            lastKnownLocationInFrames = completedFrames
                        }
                        // Released BEFORE the event, not just in the finally below: a consumer that
                        // replays on Complete has to find the transport ready to accept it.
                        endProduction(session)
                        emitEvent(AudioPlayerEvent.Complete)
                    }
                }
            } catch (e: Exception) {
                if (isCurrent(session)) {
                    emitEvent(AudioPlayerEvent.Error("Playback failed", e))
                }
            } finally {
                endProduction(session)
                mutex.withLock { _sink.stop() }
            }
        }
    }

    fun pause() {
        isPaused = true
        // Before the cancel, so the job cannot slip a Complete out in between.
        endSession()
        playbackJob?.cancel()
        runBlocking {
            mutex.withLock {
                _sink.stop()
                _sink.flush()
                emitEvent(AudioPlayerEvent.Pause)
            }
        }
    }

    suspend fun seek(framePosition: Long) = mutex.withLock {
        // Unconditional: the position an in-flight job is working from is no longer the position we
        // are at, whether or not it is still playing.
        endSession()
        val wasPlaying = playbackJob?.isActive == true
        if (wasPlaying) {
            isPaused = true
            playbackJob?.cancel()
            _sink.stop()
            _sink.flush()
        }

        reader?.seek(framePosition)
        startPosition = framePosition
        lastKnownLocationInFrames = framePosition
        sessionStartFrame = framePosition

        if (wasPlaying) play()
    }

    // Returns the smooth play cursor (sink's framePosition + session anchor) while
    // playing, falling back to the write cursor when the sink is idle. Capped by
    // lastKnownLocationInFrames so we never report past what's been queued, which
    // matters during the tail (drain) when the sink may briefly lag the writer.
    fun getLocationInFrames(): Long {
        val sink = _sink
        return if (sink.isRunning) {
            // Frames played since THIS session began (see sinkFrameBaseline) — never the sink's raw
            // counter, which on resume still holds the prior session's frames and would double the
            // anchor.
            val playedThisSession = (sink.framePosition - sinkFrameBaseline).coerceAtLeast(0L)
            if (processor.playbackRate == 1.0) {
                (sessionStartFrame + playedThisSession).coerceAtMost(lastKnownLocationInFrames)
            } else {
                // At a stretched rate, one sink-frame played corresponds to `rate` source-frames
                // (JVM: `player.framePosition * playbackRate`) — no `lastKnownLocationInFrames`
                // clamp here since the play loop keeps it in lockstep with this exact formula.
                sessionStartFrame + (playedThisSession * processor.playbackRate).toLong()
            }
        } else {
            lastKnownLocationInFrames
        }
    }

    // Debug-only: expose the underlying sink's framePosition so the UI can log
    // the play cursor (e.g. AudioTrack.playbackHeadPosition) alongside the
    // reader-side write cursor while diagnosing waveform-scroll stutter.
    fun debugSinkFramePosition(): Long = _sink.framePosition

    fun release() = runBlocking {
        mutex.withLock {
            endSession()
            playbackJob?.cancel()
            reader?.close()
            _sink.close()
        }
    }

    private companion object {
        const val FIRST_SESSION = 0L

        /** No session is producing audio. Distinct from any real session id. */
        const val NO_SESSION = -1L
    }
}
