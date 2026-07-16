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
import org.bibletranslationtools.otter.common.domain.IUndoable
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.bibletranslationtools.otter.common.domain.model.UndoableActionHistory
import org.bibletranslationtools.otter.common.domain.translation.TranslationTakeDeleteAction
import org.bibletranslationtools.otter.common.domain.translation.TranslationTakeRecordAction
import org.bibletranslationtools.otter.common.domain.translation.TranslationTakeSelectAction
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
    /** True when a take can be opened in a configured external editor (desktop only). */
    val canEditExternally: Boolean = false,
    val error: String? = null
)

/**
 * Drives the Blind Draft step for the active chunk (JVM: `BlindDraftViewModel`): plays the chunk's
 * source audio, lists its target takes ("best take" + "available takes"), and lets the translator
 * record a new draft (7b) and pick the best take (7c). Follows the shared [OratureWorkbookDataStore]
 * active-chunk selection; playback uses the shared newaudio player.
 */
class OratureBlindDraftViewModel(
    private val translationVm: OratureTranslationViewModel
) : ViewModel(), KoinComponent {

    private val workbookDataStore: OratureWorkbookDataStore by inject()
    private val playerFactory: AudioPlayerConnectionFactory by inject()
    private val recorderFactory: AudioRecorderConnectionFactory by inject()

    private val _uiState = MutableStateFlow(OratureBlindDraftUiState())
    val uiState: StateFlow<OratureBlindDraftUiState> = _uiState.asStateFlow()

    private var activeChunk: Chunk? = null
    private var takesById: Map<Int, Take> = emptyMap()
    private var sourcePlayer: IAudioPlayer? = null
    private var takePlayer: IAudioPlayer? = null

    // Take select/delete/record are undoable (JVM: BlindDraftViewModel.actionHistory). The page header's
    // undo/redo route here while Blind Draft is active (JVM: writes translationViewModel can-undo/redo).
    private val actionHistory = UndoableActionHistory<IUndoable>()

    // External-editor plugin support (JVM: edit-take-in-plugin). Desktop-only.
    private val pluginStore: org.bibletranslationtools.orature.plugins.OraturePluginStore by inject()

    /** The configured default editor plugin, if external editing is available (desktop + one selected). */
    private fun selectedEditor(): org.bibletranslationtools.orature.plugins.OratureExternalPlugin? {
        if (!org.bibletranslationtools.orature.plugins.canLaunchPlugins()) return null
        val reg = pluginStore.load()
        return reg.plugins.firstOrNull { it.id == reg.selectedEditorId && it.canEdit }
    }

    /** Open a take in the configured external editor, then reload it (edited in place). */
    fun editTakeExternally(id: Int) {
        val take = takesById[id] ?: return
        val editor = selectedEditor() ?: return
        val chunk = activeChunk ?: return
        viewModelScope.launch {
            stopAll()
            org.bibletranslationtools.orature.plugins.launchPlugin(editor, take.file, pluginParams(chunk))
            onChunk(chunk) // reload the take list (file was edited in place)
        }
    }

    /** Translation context handed to a plugin (JVM: PluginParameters). Populated with what's cheaply
     *  available; the plugin's args template selects which fields it actually receives. */
    private fun pluginParams(chunk: Chunk): org.bibletranslationtools.otter.common.domain.plugins.PluginParameters {
        val wb = workbookDataStore.activeWorkbook.value
        val chapter = workbookDataStore.activeChapter.value
        val sourceAudio = wb?.let { w ->
            chapter?.let { runCatching { w.sourceAudioAccessor.getChapter(it.sort, w.target)?.file }.getOrNull() }
        }
        return org.bibletranslationtools.otter.common.domain.plugins.PluginParameters(
            languageName = wb?.target?.language?.name ?: "",
            bookSlug = wb?.target?.slug ?: "",
            bookTitle = wb?.target?.title ?: (wb?.target?.slug ?: ""),
            chapterLabel = chapter?.title ?: chapter?.sort?.toString() ?: "",
            chapterNumber = chapter?.sort ?: 1,
            verseTotal = null,
            chunkNumber = chunk.sort,
            chunkLabel = chunk.sort.toString(),
            sourceChapterAudio = sourceAudio,
            sourceLanguageName = wb?.source?.language?.name
        )
    }

    // Recording pipeline (reuses narration's: recorder connection + WavFileWriter gated by
    // start()/pause() + ActiveRecordingRenderer for the live wave).
    private var recorder: AudioRecorderConnection? = null
    private var writer: WavFileWriter? = null
    private var activeRenderer: ActiveRecordingRenderer? = null
    private var pendingTake: Take? = null
    private val recordingActiveFlow = MutableStateFlowOf(false)
    private val emptyWave = FloatArray(RECORD_WIDTH * 2)

    init {
        // The page header undo/redo route here while this step is active.
        translationVm.setUndoRedoHandlers(::undo, ::redo)
        // Follow the shared active-chunk selection (set by the translation VM's steps-drawer nav).
        viewModelScope.launch {
            workbookDataStore.activeChunk.collect { chunk -> onChunk(chunk) }
        }
    }

    private fun onChunk(chunk: Chunk?) {
        stopAll()
        // Undo history is per-chunk (JVM: actionHistory.clear() on chunk change).
        if (chunk?.sort != activeChunk?.sort) {
            actionHistory.clear()
            translationVm.updateChunkUndoRedo(canUndo = false, canRedo = false)
        }
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
                        .map { OratureTakeCard(it.number, it.number, false) },
                    canEditExternally = selectedEditor() != null
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

    /** The configured default recorder plugin, if external recording is available. */
    private fun selectedRecorder(): org.bibletranslationtools.orature.plugins.OratureExternalPlugin? {
        if (!org.bibletranslationtools.orature.plugins.canLaunchPlugins()) return null
        val reg = pluginStore.load()
        return reg.plugins.firstOrNull { it.id == reg.selectedRecorderId && it.canRecord }
    }

    /** Build a new, un-persisted take for the active chunk (JVM: recorderViewModel.createTake). */
    private suspend fun newTake(chunk: Chunk): Take {
        val wb = workbookDataStore.activeWorkbook.value ?: error("No active workbook")
        val chapter = workbookDataStore.activeChapter.value ?: error("No active chapter")
        val takeNumber = chunk.audio.getNewTakeNumberSuspend()
        val namer = WorkbookFileNamerBuilder.createFileNamer(wb, chapter, chunk, chunk, wb.sourceMetadataSlug)
        val dir = wb.projectFilesAccessor.audioDir.resolve(namer.formatChapterNumber()).apply { mkdirs() }
        val name = namer.generateName(takeNumber, AudioFileFormat.WAV)
        return Take(name, dir.resolve(name), takeNumber, MimeType.WAV, LocalDate.now())
    }

    /**
     * Start a new recording. If an external recorder plugin is configured, launch it (JVM:
     * recordWithExternalPlugin); otherwise capture natively with the built-in recorder.
     */
    fun onRecordNew() {
        val chunk = activeChunk ?: return
        if (selectedRecorder() != null) { recordWithExternalPlugin(chunk); return }
        stopAll()
        viewModelScope.launch {
            try {
                val take = withContext(Dispatchers.IO) { newTake(chunk) }
                pendingTake = take
                val rec = AudioRecorderConnection(RECORDER_ID, recorderFactory, viewModelScope)
                rec.start(AudioSpec())
                recorder = rec
                // Initialize with an explicit format so a valid empty WAV header is written up front
                // (JVM: createTake(createEmpty = true)); the plain OratureAudioFile(file) constructor
                // parses the header and throws "file length is less than a chunk header" on an empty file.
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

    /** Record into a new take using the configured external recorder (JVM: recordWithExternalPlugin):
     *  create an empty take file, launch the recorder on it, then keep it if it has audio. */
    private fun recordWithExternalPlugin(chunk: Chunk) {
        val recorder = selectedRecorder() ?: return
        stopAll()
        viewModelScope.launch {
            val take = withContext(Dispatchers.IO) {
                val t = newTake(chunk)
                // Valid empty WAV so the external recorder has a target file (JVM: createEmpty = true).
                OratureAudioFile(t.file, 1, DEFAULT_SAMPLE_RATE, 16)
                t
            }
            org.bibletranslationtools.orature.plugins.launchPlugin(recorder, take.file, pluginParams(chunk))
            val hasAudio = runCatching { OratureAudioFile(take.file).totalFrames > 0 }.getOrDefault(false)
            if (hasAudio) {
                val op = TranslationTakeRecordAction(chunk, take, chunk.audio.getSelectedTake())
                actionHistory.execute(op)
                onUndoableAction()
            } else {
                runCatching { take.file.delete() }
            }
            onChunk(chunk)
            translationVm.onChunkTakesChanged()
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
                // Insert as an undoable record action; inserting auto-selects the new take as "best"
                // (AssociatedAudio's takes relay selects on insert). JVM: TranslationTakeRecordAction.
                val op = TranslationTakeRecordAction(chunk, take, chunk.audio.getSelectedTake())
                actionHistory.execute(op)
                onUndoableAction()
            } else {
                runCatching { take.file.delete() }
            }
            _uiState.value = _uiState.value.copy(recording = false, recordingActive = false)
            onChunk(chunk)
            translationVm.onChunkTakesChanged()
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

    /** Select the best take (JVM: onSelectTake) — undoable. */
    fun selectTake(id: Int) {
        val take = takesById[id] ?: return
        val chunk = activeChunk ?: return
        val op = TranslationTakeSelectAction(chunk, take, chunk.audio.getSelectedTake())
        actionHistory.execute(op)
        onUndoableAction()
        onChunk(chunk) // reload to reflect the new selection
        translationVm.onChunkTakesChanged()
    }

    /** Delete a take (JVM: onDeleteTake) — undoable; reselects another take if the deleted one was best. */
    fun deleteTake(id: Int) {
        val take = takesById[id] ?: return
        val chunk = activeChunk ?: return
        runCatching { takePlayer?.pause() }
        val wasSelected = chunk.audio.getSelectedTake() == take
        val op = TranslationTakeDeleteAction(chunk, take, wasSelected, ::handlePostDeleteTake)
        actionHistory.execute(op)
        onUndoableAction()
        onChunk(chunk)
        translationVm.onChunkTakesChanged()
    }

    /** After a delete: if the removed take was the selected one, promote the newest remaining take. */
    private fun handlePostDeleteTake(deleted: Take, selectAnother: Boolean) {
        if (!selectAnother) return
        val chunk = activeChunk ?: return
        chunk.audio.getAllTakes()
            .filter { !it.isDeleted() && it != deleted }
            .maxByOrNull { it.file.lastModified() }
            ?.let { chunk.audio.selectTake(it) }
    }

    fun undo() {
        if (!actionHistory.canUndo()) return
        runCatching { takePlayer?.pause() }
        actionHistory.undo()
        translationVm.updateChunkUndoRedo(canUndo = actionHistory.canUndo(), canRedo = true)
        activeChunk?.let { onChunk(it) }
        translationVm.onChunkTakesChanged()
    }

    fun redo() {
        if (!actionHistory.canRedo()) return
        runCatching { takePlayer?.pause() }
        actionHistory.redo()
        translationVm.updateChunkUndoRedo(canUndo = true, canRedo = actionHistory.canRedo())
        activeChunk?.let { onChunk(it) }
        translationVm.onChunkTakesChanged()
    }

    private fun onUndoableAction() {
        translationVm.updateChunkUndoRedo(canUndo = true, canRedo = false)
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
        translationVm.clearUndoRedoHandlers()
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
