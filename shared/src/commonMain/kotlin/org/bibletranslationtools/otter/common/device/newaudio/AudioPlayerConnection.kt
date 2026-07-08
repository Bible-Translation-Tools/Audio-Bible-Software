package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class AudioPlayerConnection(
    private val id: Int,
    private val factory: AudioPlayerConnectionFactory,
    private val scope: CoroutineScope,
    private val controlDispatcher: CoroutineDispatcher? = null
) : IAudioPlayer {

    @Volatile
    private var _reader: AudioFileReader? = null
    @Volatile
    private var lastPosition: Long = 0
    private var playbackRate: Double = 1.0

    override val frameStart: Int
        get() = _reader?.framePosition ?: 0

    override val frameEnd: Int
        get() = _reader?.totalFrames ?: 0

    override val events: Flow<AudioPlayerEvent>
        get() = factory.getPlayerWorker().events

    override fun load(reader: AudioFileReader) {
        this._reader = reader
        this.lastPosition = 0
        launchControl {
            factory.connect(id, reader, 0)
        }
    }

    override fun loadSection(reader: AudioFileReader, frameStart: Int, frameEnd: Int) {
        this._reader = reader
        this.lastPosition = frameStart.toLong()
        launchControl {
            // We assume the reader implementation handles the boundary
            // of the section internally once seeked
            factory.connect(id, reader, lastPosition)
        }
    }

    override fun play() {
        val reader = _reader ?: return
        launchControl {
            factory.connect(id, reader, lastPosition)
            val worker = factory.getPlayerWorker()
            if (worker.getLocationInFrames() >= reader.totalFrames.toLong()) {
                lastPosition = 0
                worker.seek(0)
            }
            worker.processor.setPlaybackRate(playbackRate)
            worker.play()
        }
    }

    override fun pause() {
        // Only pause if we are the one currently holding the hardware
        if (factory.isActiveConnection(id)) {
            launchControl {
                val worker = factory.getPlayerWorker()
                lastPosition = worker.getLocationInFrames()
                worker.pause()
            }
        }
    }

    override fun toggle() {
        if (isPlaying()) pause() else play()
    }

    override fun stop() {
        val shouldPause = factory.isActiveConnection(id)
        lastPosition = 0
        launchControl {
            if (shouldPause) {
                factory.getPlayerWorker().pause()
            }
            _reader?.seek(0)
        }
    }

    override fun seek(position: Int) {
        lastPosition = position.toLong()
        if (factory.isActiveConnection(id)) {
            launchControl {
                factory.getPlayerWorker().seek(lastPosition)
            }
        }
    }

    override fun changeRate(rate: Double) {
        this.playbackRate = rate
        if (factory.isActiveConnection(id)) {
            launchControl {
                factory.getPlayerWorker().processor.setPlaybackRate(rate)
            }
        }
    }

    override fun isPlaying(): Boolean {
        return factory.isActiveConnection(id) && factory.getPlayerWorker().isSinkRunning
    }

    override fun isPositionReliable(): Boolean {
        // Lock-free (called per display frame by the playback display clock).
        return factory.isActiveConnection(id) && factory.getPlayerWorker().isPositionReliable
    }

    override fun getAudioReader(): AudioFileReader? = _reader

    override fun getDurationInFrames(): Int = _reader?.totalFrames ?: 0

    override fun getDurationMs(): Int {
        val spec = _reader?.spec ?: return 0
        return spec.framesToMs(getDurationInFrames().toLong())
    }

    override fun getLocationInFrames(): Int {
        val workerPosition = factory.getPlayerWorker().getLocationInFrames().toInt()
        val duration = _reader?.totalFrames ?: 0

        if (factory.isActiveConnection(id)) {
            lastPosition = workerPosition.toLong()
            return workerPosition
        }

        val plausibleWorkerPosition = workerPosition in 0..duration && workerPosition >= lastPosition.toInt()
        return if (plausibleWorkerPosition) {
            lastPosition = workerPosition.toLong()
            workerPosition
        } else {
            lastPosition.toInt()
        }
    }

    override fun getLocationMs(): Int {
        val spec = _reader?.spec ?: return 0
        return spec.framesToMs(getLocationInFrames().toLong())
    }

    override fun close() {
        release()
    }

    private inline fun launchControl(crossinline block: suspend () -> Unit) {
        if (controlDispatcher != null) {
            scope.launch(controlDispatcher) { block() }
        } else {
            scope.launch { block() }
        }
    }

    override fun release() {
        if (factory.isActiveConnection(id)) {
            factory.getPlayerWorker().release()
        }
        _reader?.release()
        _reader = null
    }
}
