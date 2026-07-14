package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow as MutableStateFlowOf
import org.bibletranslationtools.orature.ui.workbook.OratureWorkbookDataStore
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioRecorderConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.bibletranslationtools.otter.common.recorder.ActiveRecordingRenderer
import org.bibletranslationtools.otter.common.recorder.WavFileWriter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDate

/** A single take shown in the Blind Draft take list (JVM: `TakeCardModel`/`ChunkTakeCard`). */
data class OratureTakeCard(
    val id: Int,
    val number: Int,
    val selected: Boolean
)

data class OratureBlindDraftUiState(
    val isLoading: Boolean = false,
    val hasChunk: Boolean = false,
    val chunkTitle: String = "",
    val isSourcePlaying: Boolean = false,
    /** The chunk's selected take ("best take"), if any. */
    val selectedTake: OratureTakeCard? = null,
    /** The chunk's other takes ("available takes"). */
    val availableTakes: List<OratureTakeCard> = emptyList(),
    /** The take currently playing in the list (its id), or null. */
    val playingTakeId: Int? = null,
    /** True while the recording section is shown (JVM: recordingView). */
    val recording: Boolean = false,
    /** True while actively capturing (false when paused mid-recording). */
    val recordingActive: Boolean = false,
    val error: String? = null
)

/**
 * Drives the Blind Draft step for the active chunk (JVM: `BlindDraftViewModel`): plays the chunk's
 * source audio, lists its target takes ("best take" + "available takes"), and lets the translator
 * record a new draft (7b) and pick the best take (7c). Follows the shared [OratureWorkbookDataStore]
 * active-chunk selection; playback uses the shared newaudio player.
 */
class OratureBlindDraftViewModel : ViewModel(), KoinComponent {

    private val workbookDataStore: OratureWorkbookDataStore by inject()
    private val playerFactory: AudioPlayerConnectionFactory by inject()
    private val recorderFactory: AudioRecorderConnectionFactory by inject()

    private val _uiState = MutableStateFlow(OratureBlindDraftUiState())
    val uiState: StateFlow<OratureBlindDraftUiState> = _uiState.asStateFlow()

    private var activeChunk: Chunk? = null
    private var takesById: Map<Int, Take> = emptyMap()
    private var sourcePlayer: IAudioPlayer? = null
    private var takePlayer: IAudioPlayer? = null

    // Recording pipeline (reuses narration's: recorder connection + WavFileWriter gated by
    // start()/pause() + ActiveRecordingRenderer for the live wave).
    private var recorder: AudioRecorderConnection? = null
    private var writer: WavFileWriter? = null
    private var activeRenderer: ActiveRecordingRenderer? = null
    private var pendingTake: Take? = null
    private val recordingActiveFlow = MutableStateFlowOf(false)
    private val emptyWave = FloatArray(RECORD_WIDTH * 2)

    init {
        // Follow the shared active-chunk selection (set by the translation VM's steps-drawer nav).
        viewModelScope.launch {
            workbookDataStore.activeChunk.collect { chunk -> onChunk(chunk) }
        }
    }

    private fun onChunk(chunk: Chunk?) {
        stopAll()
        activeChunk = chunk
        if (chunk == null) {
            _uiState.value = OratureBlindDraftUiState(hasChunk = false)
            return
        }
        _uiState.value = OratureBlindDraftUiState(isLoading = true, hasChunk = true)
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val takes = chunk.audio.getAllTakes()
                        .filter { !it.isDeleted() }
                        .sortedByDescending { it.file.lastModified() }
                    val selected = chunk.audio.getSelectedTake()
                    LoadedTakes(takes, selected, prepareSourcePlayer(chunk))
                }
                takesById = loaded.takes.associateBy { it.number }
                sourcePlayer = loaded.sourcePlayer
                val selectedNum = loaded.selected?.number
                _uiState.value = OratureBlindDraftUiState(
                    isLoading = false,
                    hasChunk = true,
                    chunkTitle = "${chunk.sort}",
                    selectedTake = loaded.selected?.let { OratureTakeCard(it.number, it.number, true) },
                    availableTakes = loaded.takes
                        .filter { it.number != selectedNum }
                        .map { OratureTakeCard(it.number, it.number, false) }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = OratureBlindDraftUiState(hasChunk = true, error = e.message ?: "Unknown error")
            }
        }
    }

    private data class LoadedTakes(val takes: List<Take>, val selected: Take?, val sourcePlayer: IAudioPlayer?)

    /** Load the chunk's source audio into a player (chunk-level source slicing lands with 7d). */
    private fun prepareSourcePlayer(chunk: Chunk): IAudioPlayer? {
        val wb = workbookDataStore.activeWorkbook.value ?: return null
        val chapterSort = workbookDataStore.activeChapter.value?.sort ?: return null
        val sa = runCatching {
            wb.sourceAudioAccessor.getUserMarkedChapter(chapterSort, wb.target)
                ?: wb.sourceAudioAccessor.getChapter(chapterSort, wb.target)
        }.getOrNull() ?: return null
        val reader = org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile(sa.file)
            .reader().apply { open() }
        return AudioPlayerConnection(SOURCE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
            .also { it.load(reader) }
    }

    fun toggleSource() {
        val p = sourcePlayer ?: return
        takePlayer?.pause()
        if (p.isPlaying()) p.pause() else p.play()
        _uiState.value = _uiState.value.copy(isSourcePlaying = p.isPlaying(), playingTakeId = null)
    }

    fun toggleTake(id: Int) {
        val take = takesById[id] ?: return
        sourcePlayer?.pause()
        val current = _uiState.value.playingTakeId
        takePlayer?.pause()
        if (current == id) {
            _uiState.value = _uiState.value.copy(playingTakeId = null, isSourcePlaying = false)
            return
        }
        val reader = org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile(take.file)
            .reader().apply { open() }
        val p = AudioPlayerConnection(TAKE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
        p.load(reader)
        p.play()
        takePlayer = p
        _uiState.value = _uiState.value.copy(playingTakeId = id, isSourcePlaying = false)
    }

    /** Start a new recording: create an empty take + wire the recorder/writer/live renderer. */
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
                val w = WavFileWriter(OratureAudioFile(take.file), rec.getAudioStream(), false, {}, viewModelScope)
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

    /** Pause / resume the active recording (JVM: RecordingSection toggle). */
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

    /** Finish + keep the recording: finalize the WAV, register it as a take, select it. */
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
                chunk.audio.selectTake(take)
            } else {
                runCatching { take.file.delete() }
            }
            _uiState.value = _uiState.value.copy(recording = false, recordingActive = false)
            onChunk(chunk)
        }
    }

    /** Discard the active recording. */
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

    /** Live recording waveform (min/max pairs) for the recording section. */
    fun currentRecordingWaveform(): FloatArray = activeRenderer?.floatBuffer?.array ?: emptyWave

    private fun stopRecordingPipeline() {
        runCatching { activeRenderer?.close() }
        recorder = null
        writer = null
        activeRenderer = null
        pendingTake = null
    }

    /** Select the best take (JVM: onSelectTake). Undo/redo history lands in 7c. */
    fun selectTake(id: Int) {
        val take = takesById[id] ?: return
        val chunk = activeChunk ?: return
        chunk.audio.selectTake(take)
        onChunk(chunk) // reload to reflect the new selection
    }

    private fun stopAll() {
        runCatching { sourcePlayer?.pause() }
        runCatching { sourcePlayer?.release() }
        runCatching { takePlayer?.pause() }
        runCatching { takePlayer?.release() }
        sourcePlayer = null
        takePlayer = null
        // Abort any in-progress recording (e.g. chunk switched away).
        if (writer != null || recorder != null) {
            recordingActiveFlow.value = false
            runCatching { writer?.close() }
            runCatching { recorder?.stop() }
            stopRecordingPipeline()
        }
    }

    public override fun onCleared() {
        stopAll()
    }

    companion object {
        private const val SOURCE_PLAYER_ID = 90_010
        private const val TAKE_PLAYER_ID = 90_011
        private const val RECORDER_ID = 90_012
        private const val RECORD_WIDTH = 480
        private const val RECORD_SECONDS = 10
    }
}
