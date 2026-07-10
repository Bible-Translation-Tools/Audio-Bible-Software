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
    // Whether WE have requested playback (play() called, not yet paused/stopped). The shared sink
    // can report isRunning even when this connection isn't playing (e.g. the narration player during
    // recording/re-record, where we only load+seek and never play). Position reads must trust the
    // sink worker ONLY while this is true; otherwise a stale worker position would clobber a seek.
    @Volatile
    private var playRequested: Boolean = false
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
        this.playRequested = false
        launchControl {
            factory.connect(id, reader, 0)
        }
    }

    override fun loadSection(reader: AudioFileReader, frameStart: Int, frameEnd: Int) {
        this._reader = reader
        this.lastPosition = frameStart.toLong()
        this.playRequested = false
        launchControl {
            // We assume the reader implementation handles the boundary
            // of the section internally once seeked
            factory.connect(id, reader, lastPosition)
        }
    }

    override fun play() {
        val reader = _reader ?: return
        playRequested = true
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
        playRequested = false
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
        playRequested = false
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
        return playRequested && factory.isActiveConnection(id) && factory.getPlayerWorker().isSinkRunning
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
        // Trust the sink worker's live position ONLY while we actually hold the hardware AND it is
        // producing frames. Otherwise (idle, paused, or seeked-while-stopped — e.g. the narration
        // player during recording/re-record) the worker reports a stale 0, which would make the
        // domain's getLocationInFrames() lose the seek target. In those cases return the last known
        // logical position (the seek/pause target). This mirrors Orature's
        // ChapterRepresentationConnection position-pointer semantics that narration relies on.
        val worker = factory.getPlayerWorker()
        if (playRequested && factory.isActiveConnection(id) && worker.isSinkRunning) {
            val workerPosition = worker.getLocationInFrames().toInt()
            lastPosition = workerPosition.toLong()
            return workerPosition
        }
        return lastPosition.toInt()
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
