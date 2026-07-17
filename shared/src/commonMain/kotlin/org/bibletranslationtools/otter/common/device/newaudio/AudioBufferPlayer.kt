package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AudioBufferPlayer(
    sink: AudioSink,
    val processor: AudioProcessor,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _events = MutableSharedFlow<AudioPlayerEvent>(extraBufferCapacity = 10)
    val events = _events.asSharedFlow()

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
        this.reader?.release()
        this.reader = reader.apply {
            open()
            processor.configure(spec)
            _sink.open(spec)
        }
        startPosition = 0
        lastKnownLocationInFrames = 0
        _events.emit(AudioPlayerEvent.Load)
    }

    fun play() {
        if (playbackJob?.isActive == true) return
        isPaused = false

        playbackJob = scope.launch {
            try {
                _events.emit(AudioPlayerEvent.Play)
                // Anchor the play cursor: pause/seek flushed the sink, so its
                // framePosition is about to start counting from 0.
                sessionStartFrame = lastKnownLocationInFrames
                // Start sink immediately on play so transport state is observable without race.
                mutex.withLock { _sink.start() }

                while (isActive && !isPaused) {
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
                            // The reader's raw position no longer tracks true progress once we're
                            // rewinding it for the sliding window, so derive position from what the
                            // sink has actually played, scaled by the rate (JVM:
                            // `player.framePosition * playbackRate` in getLocationInFrames).
                            lastKnownLocationInFrames = sessionStartFrame +
                                (currentSink.framePosition * processor.playbackRate).toLong()
                        }
                    }
                }

                if (!isPaused) {
                    val sinkToDrain = mutex.withLock {
                        if (reader?.hasRemaining() == false) _sink else null
                    }
                    if (sinkToDrain != null) {
                        sinkToDrain.drain()
                        val completedFrames = mutex.withLock { reader?.totalFrames?.toLong() }
                        if (completedFrames != null && completedFrames >= 0L) {
                            lastKnownLocationInFrames = completedFrames
                        }
                        _events.emit(AudioPlayerEvent.Complete)
                    }
                }
            } catch (e: Exception) {
                _events.emit(AudioPlayerEvent.Error("Playback failed", e))
            } finally {
                mutex.withLock { _sink.stop() }
            }
        }
    }

    fun pause() {
        isPaused = true
        playbackJob?.cancel()
        runBlocking {
            mutex.withLock {
                _sink.stop()
                _sink.flush()
                _events.emit(AudioPlayerEvent.Pause)
            }
        }
    }

    suspend fun seek(framePosition: Long) = mutex.withLock {
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
            if (processor.playbackRate == 1.0) {
                (sessionStartFrame + sink.framePosition).coerceAtMost(lastKnownLocationInFrames)
            } else {
                // At a stretched rate, one sink-frame played corresponds to `rate` source-frames
                // (JVM: `player.framePosition * playbackRate`) — no `lastKnownLocationInFrames`
                // clamp here since the play loop keeps it in lockstep with this exact formula.
                sessionStartFrame + (sink.framePosition * processor.playbackRate).toLong()
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
            playbackJob?.cancel()
            reader?.close()
            _sink.close()
        }
    }
}
