package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.ui.translation.ChunkingStep
import org.bibletranslationtools.orature.ui.workbook.OratureWorkbookDataStore
import org.bibletranslationtools.orature.ui.workbook.PrecomputedWaveform
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioRecorderConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.IUndoable
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.bibletranslationtools.otter.common.domain.model.UndoableActionHistory
import org.bibletranslationtools.otter.common.domain.translation.TranslationTakeApproveAction
import org.bibletranslationtools.otter.common.recorder.ActiveRecordingRenderer
import org.bibletranslationtools.otter.common.recorder.WavFileWriter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDate

private const val PEER_WAVEFORM_WIDTH = 960
private const val PEER_SECONDS_ON_SCREEN = 10

/** UI state for the Peer Edit step (JVM: `PeerEditViewModel`). */
data class OraturePeerEditUiState(
    val isLoading: Boolean = false,
    val hasChunk: Boolean = false,
    /** True when the active chunk has no selected take to review (guard; shouldn't occur once gated). */
    val noTake: Boolean = false,
    val chunkTitle: String = "",
    val isSourcePlaying: Boolean = false,
    /** Target-take playback (the center waveform). */
    val isPlaying: Boolean = false,
    /** True once this chunk has been confirmed at the peer-edit stage (JVM: chunkConfirmed). */
    val confirmed: Boolean = false,
    val recording: Boolean = false,
    val recordingActive: Boolean = false,
    val error: String? = null
)

/**
 * Drives the Peer Edit step for the active chunk (JVM: `PeerEditViewModel`): plays the chunk's source
 * audio (top), shows the chunk's selected target take as a playback waveform (center), and lets the
 * reviewer Confirm the take (advancing its checking status) or Record a replacement. Confirm and the
 * (re)record are undoable and drive the page header undo/redo. Follows the shared active-chunk
 * selection; reuses the Consume waveform pipeline + the Blind Draft recording pipeline.
 */
class OraturePeerEditViewModel(
    private val translationVm: OratureTranslationViewModel
) : ViewModel(), KoinComponent {

    private val workbookDataStore: OratureWorkbookDataStore by inject()
    private val playerFactory: AudioPlayerConnectionFactory by inject()
    private val recorderFactory: AudioRecorderConnectionFactory by inject()

    private val _uiState = MutableStateFlow(OraturePeerEditUiState())
    val uiState: StateFlow<OraturePeerEditUiState> = _uiState.asStateFlow()

    private var activeChunk: Chunk? = null
    private var selectedTake: Take? = null
    private var sourcePlayer: IAudioPlayer? = null
    private var takePlayer: IAudioPlayer? = null
    private var precomputed: PrecomputedWaveform? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var totalFrames: Int = 0
    private var positionFrames: Int = 0
    private var waveformFront: FloatArray = FloatArray(PEER_WAVEFORM_WIDTH * 2)
    private var waveformTickerJob: Job? = null

    // Recording pipeline (same shape as Blind Draft).
    private var recorder: AudioRecorderConnection? = null
    private var writer: WavFileWriter? = null
    private var activeRenderer: ActiveRecordingRenderer? = null
    private var pendingTake: Take? = null
    private val recordingActiveFlow = MutableStateFlow(false)
    private val emptyWave = FloatArray(RECORD_WIDTH * 2)

    private val actionHistory = UndoableActionHistory<IUndoable>()

    // Waveform providers read by the screen each display frame.
    fun currentWaveform(): FloatArray = waveformFront
    fun currentPosition(): Int = positionFrames
    fun currentTotalFrames(): Int = totalFrames
    fun currentRecordingWaveform(): FloatArray = activeRenderer?.floatBuffer?.array ?: emptyWave

    init {
        translationVm.setUndoRedoHandlers(::undo, ::redo)
        viewModelScope.launch {
            workbookDataStore.activeChunk.collect { chunk -> onChunk(chunk) }
        }
    }

    private fun onChunk(chunk: Chunk?) {
        stopAll()
        if (chunk?.sort != activeChunk?.sort) {
            actionHistory.clear()
            translationVm.updateChunkUndoRedo(canUndo = false, canRedo = false)
        }
        activeChunk = chunk
        if (chunk == null) {
            _uiState.value = OraturePeerEditUiState(hasChunk = false)
            return
        }
        _uiState.value = OraturePeerEditUiState(isLoading = true, hasChunk = true)
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val take = chunk.audio.getSelectedTake() ?: return@withContext null
                    val audioFile = OratureAudioFile(take.file)
                    val playerReader = audioFile.reader().apply { open() }
                    val sr = playerReader.spec.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    val peakReader = audioFile.reader().apply { open() }
                    val peaks = try {
                        PrecomputedWaveform.build(peakReader, PEER_WAVEFORM_WIDTH, PEER_SECONDS_ON_SCREEN, sr)
                    } finally {
                        runCatching { peakReader.release() }
                    }
                    Prepared(take, playerReader.totalFrames, sr, peaks, prepareSourcePlayer(chunk))
                } ?: run {
                    _uiState.value = OraturePeerEditUiState(hasChunk = true, noTake = true)
                    return@launch
                }

                selectedTake = prepared.take
                sampleRate = prepared.sampleRate
                totalFrames = prepared.totalFrames
                positionFrames = 0
                precomputed = prepared.peaks
                sourcePlayer = prepared.sourcePlayer
                val takeAudio = OratureAudioFile(prepared.take.file)
                val p = AudioPlayerConnection(TAKE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
                p.load(takeAudio.reader().apply { open() })
                takePlayer = p

                _uiState.value = OraturePeerEditUiState(
                    isLoading = false,
                    hasChunk = true,
                    chunkTitle = "${chunk.sort}",
                    confirmed = chunk.checkingStatus().ordinal >= checkingStatusForStep().ordinal
                )
                startWaveformTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = OraturePeerEditUiState(hasChunk = true, error = e.message ?: "Unknown error")
            }
        }
    }

    private data class Prepared(
        val take: Take,
        val totalFrames: Int,
        val sampleRate: Int,
        val peaks: PrecomputedWaveform,
        val sourcePlayer: IAudioPlayer?
    )

    /** Load the chapter's source audio for the top source player (chunk-level slicing lands later). */
    private fun prepareSourcePlayer(chunk: Chunk): IAudioPlayer? {
        val wb = workbookDataStore.activeWorkbook.value ?: return null
        val chapterSort = workbookDataStore.activeChapter.value?.sort ?: return null
        val sa = runCatching {
            wb.sourceAudioAccessor.getUserMarkedChapter(chapterSort, wb.target)
                ?: wb.sourceAudioAccessor.getChapter(chapterSort, wb.target)
        }.getOrNull() ?: return null
        val reader = OratureAudioFile(sa.file).reader().apply { open() }
        return AudioPlayerConnection(SOURCE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
            .also { it.load(reader) }
    }

    fun toggleSource() {
        val p = sourcePlayer ?: return
        takePlayer?.pause()
        if (p.isPlaying()) p.pause() else p.play()
        _uiState.value = _uiState.value.copy(isSourcePlaying = p.isPlaying(), isPlaying = false)
    }

    fun togglePlay() {
        val p = takePlayer ?: return
        sourcePlayer?.pause()
        if (p.isPlaying()) p.pause() else p.play()
        _uiState.value = _uiState.value.copy(isPlaying = p.isPlaying(), isSourcePlaying = false)
    }

    fun pause() {
        takePlayer?.pause()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    fun seekToFrame(frame: Int) {
        val clamped = frame.coerceIn(0, totalFrames)
        takePlayer?.seek(clamped)
        positionFrames = clamped
    }

    /** The checking status this step confirms to (JVM: checkingStatusFromStep). The one screen serves
     *  Peer Edit, Keyword Check, and Verse Check — only the applied status differs. */
    private fun checkingStatusForStep(): CheckingStatus = when (translationVm.uiState.value.selectedStep) {
        ChunkingStep.PEER_EDIT -> CheckingStatus.PEER_EDIT
        ChunkingStep.KEYWORD_CHECK -> CheckingStatus.KEYWORD
        ChunkingStep.VERSE_CHECK -> CheckingStatus.VERSE
        else -> CheckingStatus.UNCHECKED
    }

    /** Confirm the take at this checking stage (JVM: confirmChunk → TranslationTakeApproveAction). */
    fun confirmChunk() {
        val chunk = activeChunk ?: return
        val take = chunk.audio.getSelectedTake() ?: return
        val current = take.checkingState.value ?: TakeCheckingState(CheckingStatus.UNCHECKED, null)
        val op = TranslationTakeApproveAction(take, checkingStatusForStep(), current).apply {
            setUndoCallback { _uiState.value = _uiState.value.copy(confirmed = false) }
            setRedoCallback { _uiState.value = _uiState.value.copy(confirmed = true) }
        }
        actionHistory.execute(op)
        _uiState.value = _uiState.value.copy(confirmed = true)
        onUndoableAction()
        translationVm.onChunkTakesChanged()
    }

    /** Start a re-recording (JVM: onRecordNew) — same pipeline as Blind Draft. */
    fun onRecordNew() {
        val chunk = activeChunk ?: return
        val wb = workbookDataStore.activeWorkbook.value ?: return
        val chapter = workbookDataStore.activeChapter.value ?: return
        stopAll()
        viewModelScope.launch {
            try {
                val take = withContext(Dispatchers.IO) {
                    val takeNumber = chunk.audio.getNewTakeNumberSuspend()
                    val namer = WorkbookFileNamerBuilder.createFileNamer(wb, chapter, chunk, chunk, wb.sourceMetadataSlug)
                    val dir = wb.projectFilesAccessor.audioDir.resolve(namer.formatChapterNumber()).apply { mkdirs() }
                    val name = namer.generateName(takeNumber, AudioFileFormat.WAV)
                    Take(name, dir.resolve(name), takeNumber, MimeType.WAV, LocalDate.now())
                }
                pendingTake = take
                val rec = AudioRecorderConnection(RECORDER_ID, recorderFactory, viewModelScope)
                rec.start(AudioSpec())
                recorder = rec
                val takeAudio = OratureAudioFile(take.file, 1, DEFAULT_SAMPLE_RATE, 16)
                val w = WavFileWriter(takeAudio, rec.getAudioStream(), false, {}, viewModelScope)
                w.listen()
                writer = w
                activeRenderer = ActiveRecordingRenderer(
                    rec.getAudioStream(), recordingActiveFlow, RECORD_WIDTH, RECORD_SECONDS, viewModelScope
                )
                w.start()
                recordingActiveFlow.value = true
                _uiState.value = _uiState.value.copy(recording = true, recordingActive = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(recording = false, recordingActive = false, error = e.message)
            }
        }
    }

    fun toggleRecording() {
        val w = writer ?: return
        if (_uiState.value.recordingActive) {
            w.pause(); recordingActiveFlow.value = false
            _uiState.value = _uiState.value.copy(recordingActive = false)
        } else {
            w.start(); recordingActiveFlow.value = true
            _uiState.value = _uiState.value.copy(recordingActive = true)
        }
    }

    /** Keep the re-recording: finalize + insert (auto-selected); the new take is unchecked so the chunk
     *  needs confirming again (JVM: insertTake then re-navigate to PEER_EDIT). */
    fun saveRecording() {
        val chunk = activeChunk ?: return
        val take = pendingTake ?: return
        viewModelScope.launch {
            recordingActiveFlow.value = false
            withContext(Dispatchers.IO) {
                writer?.pause()
                writer?.closeAndJoin()
                runCatching { recorder?.stop() }
            }
            stopRecordingPipeline()
            val hasAudio = runCatching { OratureAudioFile(take.file).totalFrames > 0 }.getOrDefault(false)
            if (hasAudio) {
                chunk.audio.insertTake(take)
            } else {
                runCatching { take.file.delete() }
            }
            _uiState.value = _uiState.value.copy(recording = false, recordingActive = false)
            onChunk(chunk)
            translationVm.onChunkTakesChanged()
        }
    }

    fun cancelRecording() {
        val take = pendingTake
        viewModelScope.launch {
            recordingActiveFlow.value = false
            withContext(Dispatchers.IO) {
                writer?.pause()
                writer?.closeAndJoin()
                runCatching { recorder?.stop() }
            }
            stopRecordingPipeline()
            runCatching { take?.file?.delete() }
            _uiState.value = _uiState.value.copy(recording = false, recordingActive = false)
            activeChunk?.let { onChunk(it) }
        }
    }

    fun undo() {
        if (!actionHistory.canUndo()) return
        actionHistory.undo()
        translationVm.updateChunkUndoRedo(canUndo = actionHistory.canUndo(), canRedo = true)
        translationVm.onChunkTakesChanged()
    }

    fun redo() {
        if (!actionHistory.canRedo()) return
        actionHistory.redo()
        translationVm.updateChunkUndoRedo(canUndo = true, canRedo = actionHistory.canRedo())
        translationVm.onChunkTakesChanged()
    }

    private fun onUndoableAction() {
        translationVm.updateChunkUndoRedo(canUndo = true, canRedo = false)
    }

    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = viewModelScope.launch(Dispatchers.Default) {
            val out = FloatArray(PEER_WAVEFORM_WIDTH * 2)
            while (isActive) {
                val p = takePlayer
                val peaks = precomputed
                if (p != null && peaks != null) {
                    runCatching {
                        val playing = p.isPlaying()
                        if (playing) positionFrames = p.getLocationInFrames()
                        if (_uiState.value.isPlaying != playing) {
                            _uiState.value = _uiState.value.copy(isPlaying = playing)
                        }
                        val halfWindow = PEER_SECONDS_ON_SCREEN * sampleRate / 2
                        peaks.window(positionFrames - halfWindow, out)
                        waveformFront = out.copyOf()
                    }.onFailure { System.err.println("[peeredit] waveform render failed: $it") }
                }
                delay(33)
            }
        }
    }

    private fun stopRecordingPipeline() {
        runCatching { activeRenderer?.close() }
        recorder = null
        writer = null
        activeRenderer = null
        pendingTake = null
    }

    private fun stopAll() {
        waveformTickerJob?.cancel()
        runCatching { sourcePlayer?.pause() }
        runCatching { sourcePlayer?.release() }
        runCatching { takePlayer?.pause() }
        runCatching { takePlayer?.release() }
        sourcePlayer = null
        takePlayer = null
        precomputed = null
        if (writer != null || recorder != null) {
            recordingActiveFlow.value = false
            runCatching { writer?.close() }
            runCatching { recorder?.stop() }
            stopRecordingPipeline()
        }
    }

    public override fun onCleared() {
        translationVm.clearUndoRedoHandlers()
        stopAll()
    }

    companion object {
        private const val SOURCE_PLAYER_ID = 90_020
        private const val TAKE_PLAYER_ID = 90_021
        private const val RECORDER_ID = 90_022
        private const val RECORD_WIDTH = 480
        private const val RECORD_SECONDS = 10
    }
}
