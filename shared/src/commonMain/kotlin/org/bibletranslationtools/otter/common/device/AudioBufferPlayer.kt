package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

class AudioBufferPlayer(
    sink: AudioSink,
    val processor: AudioProcessor,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _events = MutableSharedFlow<AudioPlayerEvent.Owned>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

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

    /**
     * Publishes a transport event.
     *
     * Never call this while holding [mutex]. `MutableSharedFlow.emit` suspends once the buffer is full
     * and a collector has not kept up, and hosts collect these on the main thread — so an emit under the
     * lock hands a busy UI the ability to stall the audio worker, and every transport call behind it.
     * `load()` and `pause()` both used to do exactly that.
     *
     * This was found while chasing an intermittent timeout in
     * `AudioPlayerConnectionTransportTest.aConnectionsOwnEventStreamCarriesOnlyItsOwnEvents`, and it is
     * NOT the cause of it: the flake survived this change (once in 22 full-suite runs afterwards, having
     * been twice in 12 before — not a distinguishable rate). It is fixed because holding the audio mutex
     * across a suspending emit is wrong on its own, not because it fixed that.
     *
     * The buffer is sized for the bursts a person can actually produce — rapid pause/play toggling emits
     * a handful of events per second — so a collector has to be badly stalled before emission blocks at
     * all.
     */
    private suspend fun emitEvent(event: AudioPlayerEvent) {
        _events.emit(AudioPlayerEvent.Owned(eventOwner, event))
    }

    private var reader: AudioFileReader? = null
    private var playbackJob: Job? = null

    // This mutex protects both the 'sink' reference and 'reader' state
    private val mutex = Mutex()

    // Volatile because the lock-free readers below ([isPositionReliable], [getLocationInFrames]) read it
    // without the mutex — they are called per display frame from the main thread, where waiting on this
    // mutex costs dropped frames. Volatile makes those reads see a sink swap promptly instead of never.
    @Volatile
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

    /**
     * Whether the sink still holds audio THIS player wrote — i.e. whether its play cursor is a position in
     * the content we are playing.
     *
     * The alternative was to infer this from `_sink.isRunning`, and it does not work: the line is now left
     * running across pauses, completions and loads, so "running" stopped implying "holding our audio". A
     * freshly opened sink reporting a stale non-zero counter would then be read as a play cursor, and the
     * anchor would absorb it twice.
     */
    @Volatile
    private var sinkHoldsOurAudio: Boolean = false

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
        // A different device holds none of our audio, whatever its counter says.
        sinkHoldsOurAudio = false
    }

    /**
     * Takes the mutex, and blocks the calling thread to do it. Only for callers already coordinating a
     * hardware change (see [AudioPlayerConnectionFactory.updateHardwareSink]) — anything on the UI side
     * must use [isPositionReliable], however harmless one read looks. `load()` holds this mutex across
     * `reader.open()` and `sink.open()`, so "briefly" here means as long as the hardware takes to open.
     */
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

    /**
     * Whether a playback job is currently producing audio.
     *
     * Distinct from [isPositionReliable], and the two came apart when a pause stopped halting the line: the
     * sink now keeps running (and keeps reporting an honest position) through a pause, a completion and the
     * gap between takes. "Is the position trustworthy" stayed true throughout; "is it playing" did not.
     * Anything driving a play/pause control wants this one — see [AudioPlayerConnection.isPlaying], which
     * used to ask the sink and would have answered yes forever after a take finished.
     */
    val isProducing: Boolean
        get() = producingSession.get() != NO_SESSION

    /**
     * Whether audio this player wrote is still coming out of the device.
     *
     * Different again from [isProducing]: a pause ends the session (nothing is producing) but the queue is
     * only discarded once the writer has been joined, and the device keeps playing for that whole stretch
     * — 25-48ms, measured. Anything drawing a playhead wants to keep following the position through it,
     * or it stalls at the click and then jumps forward when the pause finally lands.
     */
    val isDeliveringAudio: Boolean
        get() = _sink.isRunning && sinkHoldsOurAudio

    suspend fun load(reader: AudioFileReader) {
        mutex.withLock {
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
            // sink.open() discards whatever was queued, so its cursor no longer points at anything of ours.
            sinkHoldsOurAudio = false
        }
        // Emitted after the lock is released — see [emitEvent].
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
                mutex.withLock {
                    // Anchor from where the audio actually IS. Usually that is the last known logical
                    // position: a pause, a seek and a load all empty the queue and leave that value as the
                    // truth. But a session can also start while the sink is still playing audio we wrote —
                    // a stretched-rate pause keeps its queue, and a play arriving on the heels of a
                    // completion has not emptied anything — and there the play cursor is what is real.
                    sessionStartFrame =
                        if (sinkHoldsOurAudio) playCursor() else lastKnownLocationInFrames
                    _sink.start()
                    sinkFrameBaseline = _sink.framePosition
                }

                // Reused across iterations rather than allocated per pass. This loop runs once per
                // ~23ms of audio, so a fresh array each time is steady garbage on the one path that
                // must never wait for a collector — a GC pause here is an underrun, and Android drops
                // an underrunning track off the mixer entirely. Re-allocated only when the required
                // size actually changes, which is a reader swap, not a normal iteration.
                var inputBuffer = ByteArray(0)

                while (isActive && !isPaused && isCurrent(session)) {
                    val (currentReader, currentSink) = mutex.withLock {
                        reader to _sink
                    }

                    if (currentReader == null || !currentReader.hasRemaining()) break

                    val bytesPerFrame = currentReader.spec.bytesPerFrame.coerceAtLeast(1)
                    val requiredSize = processor.inputBufferSize * bytesPerFrame
                    if (inputBuffer.size != requiredSize) inputBuffer = ByteArray(requiredSize)

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
                        // From here the sink is holding our content, so its play cursor means something.
                        // Set before the write rather than after: the write blocks until the hardware has
                        // room, and a position read during it must already see the cursor as ours.
                        sinkHoldsOurAudio = true
                        // Only pay for WSOLA time-stretching when the rate actually differs from
                        // normal speed, so the default (1.0x) playback path is unaffected. Time-
                        // stretching changes the byte count (that's the point — it changes how much
                        // audio-time a buffer covers), so the write length must track the PROCESSED
                        // buffer's own size, not the pre-processing `read` count.
                        if (processor.playbackRate == 1.0) {
                            val accepted = currentSink.write(inputBuffer, 0, read)
                            // `SourceDataLine.write` returns early if the line is stopped, flushed or
                            // closed mid-write — which a pause does. Those frames came out of the file but
                            // never reached the hardware, so counting them as played is how audio gets
                            // silently swallowed: the reader has moved past content nobody heard. Put the
                            // reader back over the remainder so the next write picks it up.
                            if (accepted < read) {
                                val unwritten = (read - accepted) / bytesPerFrame
                                if (unwritten > 0) {
                                    val rewound = (currentReader.framePosition - unwritten)
                                        .coerceAtLeast(0)
                                    currentReader.seek(rewound.toLong())
                                }
                            }
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
                // Deliberately does NOT stop the sink. A session ending means this writer is done, not
                // that the device should be halted — and halting it is the 230-310ms restart documented on
                // [pause]. The line is stopped only where that cost is unavoidable anyway: a device change
                // or a release. What ending a session does mean is that nothing is producing audio, which
                // is what [isProducing] reports.
                endProduction(session)
            }
        }
    }

    /**
     * Pauses by discarding the queue and **putting the reader back over it**, so the sound stops now and
     * resumes from the frame it stopped on.
     *
     * Two measurements shape this, and between them they rule out every other option:
     *
     * **The line must not be stopped.** On real hardware `SourceDataLine.start()` costs **230-310ms**, and
     * it costs that however the line was stopped — with a queue, after a drain, after a flush, even on the
     * very first start. A line that is never stopped costs **0ms**. Halting the device to pause buys
     * instant silence at the price of a quarter-second of dead air on the way back, which is exactly the
     * reported symptom ("it doesn't start playing until I stop toggling"), and nothing above this layer can
     * compensate for it. So the line stays running; a running line with nothing queued is silent anyway.
     *
     * **The audible position is now knowable.** The old objection to flushing was that discarding the queue
     * meant guessing where to resume from, wrong by up to a bufferful in one direction or the other. That
     * was true while the only available position was the write cursor. `AudioSink.framePosition` is now the
     * audible position — derived from elapsed time, bounded by what was written — so [playCursor] says
     * precisely which frame the listener is on, and the reader can simply be moved there. Residual error is
     * the device's own output latency below the Java buffer, measured at 6-11ms, and it errs toward
     * replaying rather than skipping.
     *
     * Which leaves no queue to reason about, no tail playing on after the click, and no restart to pay for.
     * The alternative that keeps the queue instead of flushing it was tried: the audio plays on for up to a
     * bufferful after the click while the display is already parked, and repeated pauses accumulate that
     * gap into the very complaint this is meant to fix.
     *
     * The writer is stopped first by joining the playback job — otherwise it writes on past the flush. That
     * join waits out an in-flight blocking `write()`, bounded by the free space it is waiting for, which is
     * the other reason the buffer size matters.
     */
    suspend fun pause() {
        isPaused = true
        // Before the cancel, so the job cannot slip a Complete out in between.
        endSession()
        // Join OUTSIDE the mutex: the job's own teardown takes it, so holding it here would deadlock.
        playbackJob?.cancelAndJoin()
        mutex.withLock {
            val audible = playCursor()
            val currentReader = reader
            if (currentReader != null && processor.playbackRate == 1.0) {
                _sink.flush()
                sinkHoldsOurAudio = false
                // Never forward: the play cursor cannot legitimately be past what was written, and if a
                // device ever claims otherwise, skipping content is the one outcome worth ruling out.
                val resumeFrom = audible.coerceIn(0L, currentReader.framePosition.toLong())
                // Best effort. Putting the reader back over the flushed queue refines where a resume
                // starts; it is not what makes the pause happen. A reader that cannot be seeked (a
                // closed one throws "Tried to seek before opening file") must therefore not be able
                // to abort this call — `connect()` pauses before every load, so a throw here stopped
                // the transport of every connection, permanently, until the app was restarted.
                val seeked = runCatching { currentReader.seek(resumeFrom) }
                    .onFailure {
                        LoggerFactory.getLogger(AudioBufferPlayer::class.java)
                            .warn("Could not reposition the reader on pause; resuming from the last known frame", it)
                    }
                    .isSuccess
                if (seeked) {
                    lastKnownLocationInFrames = resumeFrom
                    sessionStartFrame = resumeFrom
                }
            } else {
                // At a stretched rate the reader's position is NOT the write cursor — the loop rewinds it
                // by `processor.overlap` for WSOLA's sliding window — so there is no honest way to say how
                // much of the queue is unheard, and therefore no honest place to put the reader back to.
                // Keep the queue and let it play out instead: an overrun of up to a bufferful is wrong by
                // less than discarding audio would be.
                lastKnownLocationInFrames = audible
            }
        }
        // Emitted after the lock is released — see [emitEvent].
        emitEvent(AudioPlayerEvent.Pause)
    }

    suspend fun seek(framePosition: Long) {
        // Unconditional: the position an in-flight job is working from is no longer the position we
        // are at, whether or not it is still playing.
        endSession()
        val wasPlaying = playbackJob?.isActive == true
        if (wasPlaying) isPaused = true
        // Joined, and outside the mutex, for the same reason [pause] does it: the writer has to be
        // finished before the queue is discarded and the reader is moved, or it lands one more buffer of
        // pre-seek audio on the far side of the flush. This used to cancel without joining.
        playbackJob?.cancelAndJoin()

        mutex.withLock {
            // Unlike a pause, a seek genuinely does hold the wrong audio, so it is the one transport
            // operation that must discard. Flush WITHOUT stopping: stopping would cost the 230-310ms
            // restart documented on [pause], and buys nothing — flush alone empties the queue, measured at
            // 0ms on a running line.
            _sink.flush()
            sinkHoldsOurAudio = false

            reader?.seek(framePosition)
            startPosition = framePosition
            lastKnownLocationInFrames = framePosition
            sessionStartFrame = framePosition
        }

        if (wasPlaying) play()
    }

    // Returns the smooth play cursor (sink's framePosition + session anchor) while the sink is playing
    // audio we wrote, and the last known logical position otherwise (loaded, seeked, paused, idle).
    fun getLocationInFrames(): Long =
        if (_sink.isRunning && sinkHoldsOurAudio) playCursor() else lastKnownLocationInFrames

    /**
     * The frame under the play cursor, in content frames.
     *
     * There used to be a `coerceAtMost(lastKnownLocationInFrames)` here, to stop this reporting past what
     * had been handed to the hardware. That bound now lives where it is actually enforceable — see
     * [AudioSink.framePosition], which no sink may advance beyond what it was written — and expressing it
     * twice was worse than expressing it once: with the queue retained across a pause, the reader and the
     * play cursor legitimately differ by a bufferful, so the second copy of the bound clamped a correct
     * position down to a stale one for as long as it took the writer to catch up.
     */
    private fun playCursor(): Long {
        // Frames played since THIS session began (see sinkFrameBaseline) — never the sink's raw counter,
        // which on resume still holds the prior session's frames and would double the anchor.
        val playedThisSession = (_sink.framePosition - sinkFrameBaseline).coerceAtLeast(0L)
        return if (processor.playbackRate == 1.0) {
            sessionStartFrame + playedThisSession
        } else {
            // At a stretched rate, one sink-frame played corresponds to `rate` source-frames
            // (JVM: `player.framePosition * playbackRate`).
            sessionStartFrame + (playedThisSession * processor.playbackRate).toLong()
        }
    }

    // Debug-only: expose the underlying sink's framePosition so the UI can log
    // the play cursor (e.g. AudioTrack.playbackHeadPosition) alongside the
    // reader-side write cursor while diagnosing waveform-scroll stutter.
    fun debugSinkFramePosition(): Long = _sink.framePosition

    /**
     * Stops playback and lets go of the current take. Deliberately does NOT close the sink.
     *
     * The sink is the audio system's, not the worker's: it is created by the hardware provider and
     * owned by [AudioPlayerConnectionFactory], which is the only thing that may open, swap or close
     * it. The worker is handed one to write into.
     *
     * It used to close it here, and because this is reachable from a per-screen
     * [AudioPlayerConnection], leaving one screen for another closed the shared output device — after
     * the incoming screen had already opened it, since navigation constructs the new screen before
     * clearing the old one. Playback then did nothing, on every screen, until the app was restarted.
     * Closing the line is a decision about the audio *configuration*; a screen going away is not one.
     */
    fun release() = runBlocking { releaseContent() }

    /** [release] for callers already inside a coroutine. */
    suspend fun releaseContent() {
        mutex.withLock {
            endSession()
            playbackJob?.cancel()
            reader?.close()
            reader = null
            sinkHoldsOurAudio = false
        }
    }

    private companion object {
        const val FIRST_SESSION = 0L

        /**
         * Room for a burst of transport events before [emitEvent] can block at all. Was 10, which a
         * rapid toggle can fill on its own.
         */
        const val EVENT_BUFFER_CAPACITY = 64

        /** No session is producing audio. Distinct from any real session id. */
        const val NO_SESSION = -1L
    }
}
