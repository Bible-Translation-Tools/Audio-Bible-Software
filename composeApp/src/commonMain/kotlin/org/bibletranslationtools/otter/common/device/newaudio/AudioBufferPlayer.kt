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

    /**
     * Updates the hardware sink safely.
     * If the loop is running, the next iteration will wait for this to finish.
     */
    suspend fun setSink(newSink: AudioSink) = mutex.withLock {
        _sink = newSink
    }

    val isSinkRunning: Boolean
        get() = runBlocking { mutex.withLock { _sink.isRunning } }

    suspend fun load(reader: AudioFileReader) = mutex.withLock {
        this.reader?.release()
        this.reader = reader.apply {
            open()
            processor.configure(spec)
            _sink.open(spec)
        }
        startPosition = 0
        _events.emit(AudioPlayerEvent.Load)
    }

    fun play() {
        if (playbackJob?.isActive == true) return
        isPaused = false

        playbackJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                _events.emit(AudioPlayerEvent.Play)
                // Start sink immediately on play so transport state is observable without race.
                mutex.withLock { _sink.start() }

                while (isActive && !isPaused) {
                    val (currentReader, currentSink) = mutex.withLock {
                        reader to _sink
                    }

                    if (currentReader == null || !currentReader.hasRemaining()) break

                    val inputBuffer = ByteArray(processor.inputBufferSize * currentReader.spec.bytesPerFrame)
                    val read = currentReader.getPcmBuffer(inputBuffer)

                    if (read > 0) {
                        val output = inputBuffer //processor.process(inputBuffer.copyOf(read))
                        currentSink.write(output, 0, read)
                    }
                }

                if (!isPaused) {
                    val sinkToDrain = mutex.withLock {
                        if (reader?.hasRemaining() == false) _sink else null
                    }
                    if (sinkToDrain != null) {
                        sinkToDrain.drain()
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

        if (wasPlaying) play()
    }

    fun getLocationInFrames(): Long = runBlocking {
        mutex.withLock {
            val sinkFramePosition = _sink.framePosition
            val sinkLocation = startPosition + sinkFramePosition
            val readerLocation = reader?.framePosition?.toLong()?.let {
                if (it == 0L) startPosition else it
            }
            if (sinkFramePosition > 0L) sinkLocation else (readerLocation ?: sinkLocation)
        }
    }

    fun release() = runBlocking {
        mutex.withLock {
            playbackJob?.cancel()
            reader?.close()
            _sink.close()
        }
    }
}
