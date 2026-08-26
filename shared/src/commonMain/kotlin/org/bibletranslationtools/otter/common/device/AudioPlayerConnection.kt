package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

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

    /**
     * Whether the worker still has to be pointed at our content before it can play.
     *
     * `factory.connect()` is a content operation — it stops the worker, releases and reopens the reader,
     * reopens the hardware line, and seeks. Doing it on every `play()` meant paying all of that to resume a
     * pause, which is not a content change at all: the take, the position and the device are the ones the
     * worker is already holding. Measured, that reload was most of the per-resume dead time, and under
     * rapid toggling it was the thing that left the transport stopped.
     *
     * So it is tracked instead of assumed. Anything that makes the worker's content or position stale sets
     * this; `play()` clears it once it has reconnected. Pausing does not set it — that is the whole point.
     */
    @Volatile
    private var needsConnect: Boolean = true


    override val frameStart: Int
        get() = _reader?.framePosition ?: 0

    override val frameEnd: Int
        get() = _reader?.totalFrames ?: 0

    // This connection's own events, not the shared worker's whole stream — see
    // AudioPlayerConnectionFactory.eventsFor.
    override val events: Flow<AudioPlayerEvent>
        get() = factory.eventsFor(id)

    override fun load(reader: AudioFileReader) {
        this._reader = reader
        this.lastPosition = 0
        this.playRequested = false
        this.needsConnect = true
        launchControl {
            factory.connect(id, reader, 0)
        }
    }

    override fun loadSection(reader: AudioFileReader, frameStart: Int, frameEnd: Int) {
        this._reader = reader
        this.lastPosition = frameStart.toLong()
        this.playRequested = false
        this.needsConnect = true
        launchControl {
            // We assume the reader implementation handles the boundary
            // of the section internally once seeked
            factory.connect(id, reader, lastPosition)
        }
    }

    override fun play() {
        val reader = _reader ?: run {
            LoggerFactory.getLogger(AudioPlayerConnection::class.java)
                .warn("play() on connection $id with no reader loaded; nothing to play")
            return
        }
        playRequested = true
        launchControl {
            // Rewind a finished take BEFORE connecting, not after.
            //
            // connect() does load(reader) + seek(position), so connecting at the end position hands
            // AudioBufferPlayer.play() a reader with nothing remaining. Its loop breaks on the first
            // iteration without reading, then still falls into the drain block and emits Complete —
            // so the worker reports Play immediately followed by Complete and no audio, which the UI
            // shows as a jump to the end with the transport flipped back to paused.
            //
            // The worker probe below cannot prevent that on its own: it reads through
            // coerceAtMost(lastKnownLocationInFrames) and the playback-rate scaling, so it sometimes
            // reports just under totalFrames and the rewind is skipped. That is why replaying a
            // finished take failed only sometimes.
            val worker = factory.getPlayerWorker()
            val total = reader.totalFrames.toLong()
            val active = factory.isActiveConnection(id)
            // Asking the worker BEFORE deciding whether to reconnect, not after. Our own lastPosition is
            // often still 0 at this point — nothing updates it unless the host polls the position — so on
            // a replay it is the worker's position that knows the take is finished, and if that answer
            // arrives after the decision it arrives too late to affect it. Getting this the wrong way
            // round makes the replay emit Play + Complete with no audio.
            if (lastPosition >= total || (active && worker.getLocationInFrames() >= total)) {
                lastPosition = 0
                // Rewinding IS a content change: the reader has nothing remaining and only connect()
                // can put it back to the start.
                needsConnect = true
            }
            // Resume, not restart: when the worker is already holding our take at our position, the only
            // thing that has to happen is that the line starts again. Everything connect() would do —
            // stop, release, reopen, seek, flush — is work against content that has not changed, and it is
            // what a pause/play pair used to cost. Losing the hardware to another connection is a content
            // change too, hence the ownership check.
            if (needsConnect || !active) {
                factory.connect(id, reader, lastPosition)
                // Again after connecting: connect() skips its reload when it judges nothing changed, which
                // it can get wrong when a DIFFERENT connection left the worker at the end.
                if (worker.getLocationInFrames() >= total) {
                    lastPosition = 0
                    worker.seek(0)
                }
                needsConnect = false
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
                // AFTER, not before. Pausing is not instantaneous — it joins a playback loop that may be
                // blocked in a write, and audio keeps coming out of the device for that whole stretch, so
                // a position read beforehand is stale by however long the join took. Measured: 29-48ms
                // behind the frame the take actually stopped on.
                //
                // The damage was visible twice over. While paused, the drawn playhead sat that far behind
                // where the sound stopped. And on resume the connection went back to reading the worker
                // and the position jumped forward by the gap in a single frame — a forward jump on every
                // resume, which is the shape of bug this whole rework exists to remove. It also accounted
                // for the entire pause/resume "shortfall": ~35ms per cycle of position that appeared out
                // of nowhere the moment play was pressed.
                //
                // Afterwards the worker reports its rewind point — the exact frame it put the reader back
                // to — which is the only position a resume can honestly start from.
                worker.pause()
                lastPosition = worker.getLocationInFrames()
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
        // The rewind below moves the reader out from under the worker without telling it where it now is,
        // so the worker has to be reconnected before it can play again. Unlike pause(), this IS a content
        // change.
        needsConnect = true
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
            // The worker seeks itself — flushing the stale queue and re-anchoring — so it ends up pointed
            // exactly where a connect() would have put it, without the reload.
            launchControl {
                factory.getPlayerWorker().seek(lastPosition)
            }
        } else {
            // Nobody moved the worker, so the new position only exists here. It has to be handed over
            // before playing.
            needsConnect = true
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
        // isProducing, not the sink's state. The sink is left running across pauses and completions
        // (stopping it costs a 230-310ms restart — see AudioBufferPlayer.pause), so "the hardware is
        // running" stopped being an answer to "is it playing" and would have stayed true forever once a
        // take finished. Lock-free either way: callers are UI-side and the worker's mutex is held across
        // hardware opens.
        return playRequested && factory.isActiveConnection(id) &&
            factory.getPlayerWorker().isProducing
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
        // Follow the worker while WE asked for playback, or while the device is still delivering audio
        // we wrote. That second clause is the fix for a visible jump: `playRequested` is cleared the
        // instant pause() is called, but the pause has to join the writer before it can discard the
        // queue, and the device plays on for that whole stretch — 25-48ms, measured. Gating on
        // playRequested alone froze the reading at the click and then jumped forward to catch up when
        // the pause landed.
        //
        // Kept rather than dropped, though, because it is doing real work: an idle or
        // seeked-while-stopped worker must not be read at all (narration relies on the seek target
        // surviving, mirroring Orature's ChapterRepresentationConnection pointer semantics), and
        // isDeliveringAudio is false in exactly those states. Ownership is checked either way, so
        // another connection's playback can never be read as ours.
        // Lock-free throughout: the display clock calls this once per display frame on the main thread,
        // so isSinkRunning's `runBlocking { mutex.withLock { … } }` would stall the UI for as long as the
        // worker holds its mutex — measured at 427ms across a load(), which is ~25 dropped frames, seen
        // as the playhead freezing while the audio plays on untroubled.
        val worker = factory.getPlayerWorker()
        val following = playRequested || worker.isDeliveringAudio
        if (following && factory.isActiveConnection(id) && worker.isPositionReliable) {
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

    /**
     * Control calls run one at a time, in the order they were made: each queued call joins the previous
     * one before doing anything.
     *
     * They used to be a bare `launch` each, which gives neither ordering nor exclusion —
     * `Dispatchers.Default` is multi-threaded, and the factory's mutex serialises `connect()` alone. But
     * `pause()` reads the position and *then* pauses the worker, while `play()` connects and *then* plays
     * it, so those pairs could interleave either way round. Toggle the transport quickly and a stale pause
     * lands after a later play and kills it; do it repeatedly and playback never starts at all, which is
     * what was reported and what `source=0` for eight seconds in the perf log recorded.
     *
     * A mutex alone cannot fix this: whichever call reaches the mutex first wins, and on a multi-threaded
     * dispatcher that need not be the one issued first. The chain is built here, synchronously, in call
     * order — which is the only place that order still exists.
     *
     * Every job still completes, so the connection adds nothing permanent to [scope]. An earlier version
     * used a long-lived queue consumer instead and deadlocked any structured scope waiting on its
     * children — `runTest` hung for its full timeout.
     */
    @Volatile
    private var previousControl: Job? = null

    private fun launchControl(block: suspend () -> Unit) {
        val prior = previousControl
        previousControl = if (controlDispatcher != null) {
            scope.launch(controlDispatcher) { runAfter(prior, block) }
        } else {
            scope.launch { runAfter(prior, block) }
        }
    }

    private suspend fun runAfter(prior: Job?, block: suspend () -> Unit) {
        prior?.join()
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // One failed control call must not stall the ones queued behind it: this job still completes,
            // so the next in the chain proceeds. But it must not vanish either — every transport call
            // goes through here, so a throw from `connect()` (opening the device, loading the take)
            // was the transport simply not happening, with nothing logged anywhere to say why.
            LoggerFactory.getLogger(AudioPlayerConnection::class.java)
                .error("Audio control call failed on connection $id; transport did not happen", e)
        }
    }

    /**
     * Gives up this connection's own resources. It must NOT tear down the worker or the sink: those
     * are app-scoped singletons shared by every screen, and this is called from a ViewModel's
     * `onCleared`.
     *
     * It used to call `worker.release()`, which closes the hardware line. Leaving one screen for
     * another therefore closed the output device out from under the screen taking over — and
     * because the replacement's `load()` (which opens the line) runs BEFORE the outgoing
     * ViewModel is cleared, the close landed after the open and nothing reopened it. Play then did
     * nothing at all, on that screen and on every screen after it, until the app was restarted.
     *
     * Stopping the audio is still right when the content being torn down is what is actually
     * playing, so that leaving a screen silences it. That is a question about the loaded READER,
     * not about the connection id: the screens use fixed ids, so the outgoing connection and its
     * replacement share one and the id alone would also match the take that just took over.
     */
    override fun release() {
        if (factory.isActiveConnection(id) && factory.holdsReader(_reader)) {
            // Ours is the take the audio system is holding, so the audio system is what lets go of
            // it — stopping playback, closing the reader and forgetting it, as one step under its
            // own lock. Closing `_reader` here instead would pull it out from under the worker,
            // which still uses it on the next connect().
            //
            // Blocking, as the `worker.release()` this replaced already was: the caller is
            // `onCleared`, whose scope is being cancelled, so anything merely launched would never
            // run. Bounded by the sink's buffer depth (50ms) — see AudioBufferPlayer.pause.
            runBlocking { factory.releaseLoadedContent() }
        } else {
            // Never handed over (or already superseded): ours alone to close.
            _reader?.release()
        }
        _reader = null
    }
}
