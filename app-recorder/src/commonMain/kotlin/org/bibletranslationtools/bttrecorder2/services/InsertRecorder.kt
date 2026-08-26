package org.bibletranslationtools.bttrecorder2.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.device.AudioRecorderConnection
import org.bibletranslationtools.otter.common.device.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.bibletranslationtools.otter.common.recorder.ActiveRecordingRenderer
import org.bibletranslationtools.otter.common.recorder.WavFileWriter
import org.slf4j.LoggerFactory
import java.io.File

/** A finished insert clip: the WAV that was captured plus its length. */
data class RecordedClip(val file: File, val frames: Int)

/**
 * Captures a short clip from the mic so it can be spliced into a take at the playhead (the playback
 * page's insert). Owns only the record side — the mic worker, the WAV writer, and the live waveform
 * renderer — and hands back a finished file; splicing and undo/redo belong to the edit session.
 *
 * The clip is always recorded at the TAKE's spec (sample rate / channels / bit depth), because the
 * spliced timeline is read back through a single reader whose spec comes from the take: a clip
 * captured at a different rate would play at the wrong speed and throw off all the frame math.
 */
class InsertRecorder(
    recorderFactory: AudioRecorderConnectionFactory,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(InsertRecorder::class.java)

    /**
     * This session's claim on the microphone, held through a connection so the factory arbitrates
     * between it and the record screen by id — the two are live together for the length of a
     * navigation transition, and each must only ever release the microphone it actually holds.
     */
    private val recorder = AudioRecorderConnection(
        id = RECORDER_ID,
        factory = recorderFactory,
        scope = scope
    )

    /** The mic stream this session is capturing — see [AudioRecorderConnection.getAudioStream]. */
    val audioStream: Flow<ByteArray> get() = recorder.getAudioStream()

    private val _isRecording = MutableStateFlow(false)
    /** Drives both the WAV writer's gate and the live renderer's "is active" state. */
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _renderer = MutableStateFlow<ActiveRecordingRenderer?>(null)
    val renderer: StateFlow<ActiveRecordingRenderer?> = _renderer.asStateFlow()

    private var writer: WavFileWriter? = null
    private var clipFile: File? = null
    private var micOpen = false

    // Captured length so far, used to position the live waveform's write head. Measured from the
    // wall clock rather than the file (whose header frame count isn't final until close) — it only
    // drives the viewport; the actual splice uses the finished file's real length.
    private var recordedNanos: Long = 0L
    private var resumedAtNanos: Long = 0L
    private var sampleRate: Int = 44100

    /** True between [begin] and [finish]/[discard]. */
    val isActive: Boolean get() = clipFile != null

    /**
     * Opens the mic at [spec] and prepares [clipFile] for writing. Capture stays PAUSED so the user
     * sees a live meter/waveform before committing to record (matching the record screen).
     */
    suspend fun begin(clipFile: File, spec: AudioSpec, waveformWidth: Int) {
        discard() // never leave a previous attempt half-open

        this.clipFile = clipFile

        _renderer.value = ActiveRecordingRenderer(
            recorder.getAudioStream(),
            isRecording,
            waveformWidth,
            SECONDS_ON_SCREEN,
            scope
        )

        // Write the clip with the take's own format so the spliced timeline stays coherent.
        val clipAudio = OratureAudioFile(clipFile, spec.channels, spec.sampleRate, spec.bitDepth)
        writer = WavFileWriter(
            oratureAudioFile = clipAudio,
            audioStream = recorder.getAudioStream(),
            append = false,
            onComplete = {},
            scope = scope
        ).also { it.listen() }

        sampleRate = spec.sampleRate
        recordedNanos = 0L
        // Awaited: a mic that will not open has to surface to the caller opening the insert overlay,
        // not disappear into a launched coroutine.
        recorder.startAndJoin(spec)
        micOpen = true
    }

    /** Begin (or resume) capturing into the clip. */
    fun resume() {
        if (!isActive) return
        resumedAtNanos = System.nanoTime()
        _isRecording.value = true
        writer?.start()
    }

    /** Stop capturing but keep the mic open (so the meter stays live), as the record screen does. */
    fun pause() {
        if (_isRecording.value) recordedNanos += System.nanoTime() - resumedAtNanos
        _isRecording.value = false
        writer?.pause()
    }

    /**
     * Closes the clip and releases the mic.
     * @return the recorded clip, or null when nothing was captured (so callers can skip the splice).
     */
    suspend fun finish(): RecordedClip? {
        val file = clipFile ?: return null
        closeMicAndWriter()

        val frames = runCatching { OratureAudioFile(file).totalFrames }.getOrDefault(0)
        clipFile = null
        _renderer.value = null
        return if (frames > 0) {
            RecordedClip(file, frames)
        } else {
            runCatching { file.delete() }
            null
        }
    }

    /** Abandons the attempt: releases the mic and deletes the partial clip. */
    suspend fun discard() {
        val file = clipFile
        closeMicAndWriter()
        clipFile = null
        _renderer.value = null
        file?.let { runCatching { it.delete() } }
    }

    /**
     * [discard] for a caller whose scope is already gone — `PlaybackViewModel.onCleared`, where
     * androidx has cancelled `viewModelScope` (and therefore [scope]) before `onCleared` is even
     * called. A `launch { discard() }` there never ran at all, so leaving the playback page with an
     * insert open leaked the microphone: on Windows a capture line is exclusive, and the next screen
     * to want the mic could not allocate one.
     *
     * Nothing here suspends, so nothing can be dropped half-way: the mic goes back through
     * [AudioRecorder.releaseAsync], which does not need this scope to survive.
     */
    fun discardOnTeardown() {
        _isRecording.value = false
        if (micOpen) {
            recorder.stop()
            micOpen = false
        }
        runCatching { writer?.close() }
            .onFailure { logger.error("Error closing the insert clip writer during teardown", it) }
        writer = null

        val file = clipFile
        clipFile = null
        _renderer.value = null
        file?.let { runCatching { it.delete() } }
    }

    /** Approximate frames captured so far (viewport positioning only — see [recordedNanos]). */
    fun recordedFramesSoFar(): Int {
        val live = if (_isRecording.value) System.nanoTime() - resumedAtNanos else 0L
        return ((recordedNanos + live) / 1_000_000_000.0 * sampleRate).toInt().coerceAtLeast(0)
    }

    private suspend fun closeMicAndWriter() {
        if (_isRecording.value) recordedNanos += System.nanoTime() - resumedAtNanos
        _isRecording.value = false
        if (micOpen) {
            // Awaited: the writer is closed and the clip measured right below, so a mic still
            // delivering packets would land them after the header was finalised.
            runCatching { recorder.stopAndJoin() }
                .onFailure { logger.error("Error stopping the insert recorder", it) }
            micOpen = false
        }
        runCatching { writer?.closeAndJoin() }
            .onFailure { logger.error("Error closing the insert clip writer", it) }
        writer = null
    }

    companion object {
        /** Matches the playback/record waveform zoom so the live clip scrolls at the same rate. */
        private const val SECONDS_ON_SCREEN = 10

        /** This session's connection id. Distinct from the record screen's, which competes for the mic. */
        private const val RECORDER_ID = 80_020
    }
}
