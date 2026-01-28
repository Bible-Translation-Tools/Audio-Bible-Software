package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class AudioPlayerConnection(
    private val id: Int,
    private val factory: AudioPlayerConnectionFactory,
    private val scope: CoroutineScope
) : IAudioPlayer {

    private var _reader: AudioFileReader? = null
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
        scope.launch {
            factory.connect(id, reader, 0)
        }
    }

    override fun loadSection(reader: AudioFileReader, frameStart: Int, frameEnd: Int) {
        this._reader = reader
        this.lastPosition = frameStart.toLong()
        scope.launch {
            // We assume the reader implementation handles the boundary
            // of the section internally once seeked
            factory.connect(id, reader, lastPosition)
        }
    }

    override fun play() {
        val reader = _reader ?: return
        scope.launch {
            factory.connect(id, reader, lastPosition)
            val worker = factory.getPlayerWorker()
            worker.processor.setPlaybackRate(playbackRate)
            worker.play()
        }
    }

    override fun pause() {
        val worker = factory.getPlayerWorker()
        // Only pause if we are the one currently holding the hardware
        if (factory.isActiveConnection(id)) {
            lastPosition = worker.getLocationInFrames()
            worker.pause()
        }
    }

    override fun toggle() {
        if (isPlaying()) pause() else play()
    }

    override fun stop() {
        if (factory.isActiveConnection(id)) {
            factory.getPlayerWorker().pause()
        }
        lastPosition = 0
        scope.launch {
            _reader?.seek(0)
        }
    }

    override fun seek(position: Int) {
        lastPosition = position.toLong()
        if (factory.isActiveConnection(id)) {
            scope.launch {
                factory.getPlayerWorker().seek(lastPosition)
            }
        }
    }

    override fun changeRate(rate: Double) {
        this.playbackRate = rate
        if (factory.isActiveConnection(id)) {
            factory.getPlayerWorker().processor.setPlaybackRate(rate)
        }
    }

    override fun isPlaying(): Boolean {
        return factory.isActiveConnection(id) && factory.getPlayerWorker().isSinkRunning
    }

    override fun getAudioReader(): AudioFileReader? = _reader

    override fun getDurationInFrames(): Int = _reader?.totalFrames ?: 0

    override fun getDurationMs(): Int {
        val spec = _reader?.spec ?: return 0
        return spec.framesToMs(getDurationInFrames().toLong())
    }

    override fun getLocationInFrames(): Int {
        return if (factory.isActiveConnection(id)) {
            factory.getPlayerWorker().getLocationInFrames().toInt()
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

    override fun release() {
        if (factory.isActiveConnection(id)) {
            factory.getPlayerWorker().release()
        }
        _reader?.release()
        _reader = null
    }
}