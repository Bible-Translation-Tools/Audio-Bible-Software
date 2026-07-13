package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.ui.workbook.OratureWorkbookDataStore
import org.bibletranslationtools.orature.ui.workbook.PrecomputedWaveform
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.audio.ChunkMarker
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.CreateChunks
import org.bibletranslationtools.otter.common.domain.content.ResetChunks
import org.bibletranslationtools.otter.common.domain.model.DEFAULT_CHUNK_MARKER_TOTAL
import org.bibletranslationtools.otter.common.domain.model.MarkerItem
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementModel
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementType
import org.bibletranslationtools.otter.common.domain.translation.ChunkAudioUseCase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

private const val CHUNK_WAVEFORM_WIDTH = 960
private const val CHUNK_SECONDS_ON_SCREEN = 10
// Minimum gap (frames) between the playhead and an existing marker for "Add Chunk" to be allowed
// (JVM: MARKER_WIDTH_APPROX in pixels). ~0.25s keeps markers from stacking.
private const val CHUNK_MIN_GAP_FRAMES = 11_025

data class OratureChunkingUiState(
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** True when the playhead is too close to an existing marker (disables Add Chunk). */
    val addDisabled: Boolean = false,
    /** The placed chunk markers — reactive so add/delete recomposes the overlay. */
    val markers: List<OratureMarkerInfo> = emptyList(),
    val error: String? = null
)

/**
 * Drives the Chunking step: place/move/delete chunk-boundary markers on the chapter's source audio,
 * with undo/redo and save. Mirrors the JVM `ChunkingViewModel` — the marker engine is the shared
 * [MarkerPlacementModel] (CHUNK), playback + waveform reuse the Consume path, and save writes the
 * chunks via [ResetChunks] + [CreateChunks] + [ChunkAudioUseCase]. [translationVm] is updated so the
 * page header's undo/redo reflect this step (JVM: chunking VM writes translationViewModel).
 */
class OratureChunkingViewModel(
    private val chapterSort: Int,
    private val translationVm: OratureTranslationViewModel
) : ViewModel(), KoinComponent {

    private val workbookDataStore: OratureWorkbookDataStore by inject()
    private val playerFactory: AudioPlayerConnectionFactory by inject()
    private val directoryProvider: IDirectoryProvider by inject()
    private val createChunks: CreateChunks by inject()
    private val resetChunks: ResetChunks by inject()

    private val _uiState = MutableStateFlow(OratureChunkingUiState())
    val uiState: StateFlow<OratureChunkingUiState> = _uiState.asStateFlow()

    private var player: IAudioPlayer? = null
    private var precomputed: PrecomputedWaveform? = null
    private var markerModel: MarkerPlacementModel? = null

    private var workbook: Workbook? = null
    private var chapter: Chapter? = null
    private var sourceFile: File? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var totalFrames: Int = 0
    private var positionFrames: Int = 0
    private var markerInfos: List<OratureMarkerInfo> = emptyList()
    private var waveformFront: FloatArray = FloatArray(CHUNK_WAVEFORM_WIDTH * 2)

    private var waveformTickerJob: Job? = null
    // Save must outlive the VM (it's triggered as the VM is torn down on navigate).
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun currentWaveform(): FloatArray = waveformFront
    fun currentPosition(): Int = positionFrames
    fun currentTotalFrames(): Int = totalFrames
    fun currentMarkers(): List<OratureMarkerInfo> = markerInfos

    init {
        // The header undo/redo route here while this step is active (JVM: translationViewModel).
        translationVm.setUndoRedoHandlers(::undo, ::redo)
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = OratureChunkingUiState(isLoading = true)
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val wb = workbookDataStore.activeWorkbook.value ?: error("No active workbook")
                    val chap = wb.target.chapters.toList().blockingGet().firstOrNull { it.sort == chapterSort }
                        ?: error("No chapter $chapterSort")
                    // Source audio must live in the project so it can be re-chunked (JVM: dock()).
                    // The project RC (manifest.yaml) is scaffolded on open (OratureWorkbookDataStore).
                    val accessor = wb.projectFilesAccessor
                    val userMarked = wb.sourceAudioAccessor.getUserMarkedChapter(chapterSort, wb.target)
                    val file = (userMarked ?: run {
                        wb.sourceAudioAccessor.getChapter(chapterSort, wb.target)?.let {
                            ChunkAudioUseCase(directoryProvider, accessor).copySourceAudioToProject(it.file)
                        }
                        wb.sourceAudioAccessor.getUserMarkedChapter(chapterSort, wb.target)
                    })?.file ?: error("No source audio for chapter $chapterSort")

                    val audioFile = OratureAudioFile(file)
                    audioFile.clearCues()
                    val existing = audioFile.getMarker<ChunkMarker>().map { MarkerItem(it, true) }
                    val model = MarkerPlacementModel(
                        MarkerPlacementType.CHUNK,
                        audioFile,
                        (1..DEFAULT_CHUNK_MARKER_TOTAL).map { ChunkMarker(it, 0) }
                    ).apply { loadMarkers(existing) }
                    val playerReader = audioFile.reader().apply { open() }
                    val sr = playerReader.spec.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    // Decode the whole file's peaks ONCE here (off the main thread) so the ticker never
                    // re-decodes per frame. A separate reader is used + released after the single pass.
                    val peakReader = audioFile.reader().apply { open() }
                    val peaks = try {
                        PrecomputedWaveform.build(peakReader, CHUNK_WAVEFORM_WIDTH, CHUNK_SECONDS_ON_SCREEN, sr)
                    } finally {
                        runCatching { peakReader.release() }
                    }
                    Prepared(wb, chap, file, playerReader, sr, peaks, model)
                }

                workbook = prepared.workbook
                chapter = prepared.chapter
                sourceFile = prepared.file
                markerModel = prepared.model
                sampleRate = prepared.sampleRate
                totalFrames = prepared.playerReader.totalFrames
                precomputed = prepared.peaks
                val p = AudioPlayerConnection(PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
                p.load(prepared.playerReader)
                player = p

                refreshMarkers()
                _uiState.value = _uiState.value.copy(isLoading = false)
                startWaveformTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = OratureChunkingUiState(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    private data class Prepared(
        val workbook: Workbook,
        val chapter: Chapter,
        val file: File,
        val playerReader: AudioFileReader,
        val sampleRate: Int,
        val peaks: PrecomputedWaveform,
        val model: MarkerPlacementModel
    )

    fun placeMarker() {
        val model = markerModel ?: return
        model.addMarker(positionFrames)
        refreshMarkers()
    }

    fun moveMarker(id: Int, newFrame: Int) {
        val model = markerModel ?: return
        val original = model.markerItems.firstOrNull { it.id == id }?.frame ?: return
        model.moveMarker(id, original, newFrame.coerceIn(0, totalFrames))
        refreshMarkers()
    }

    fun deleteMarker(id: Int) {
        markerModel?.deleteMarker(id)
        refreshMarkers()
    }

    fun undo() {
        markerModel?.undo()
        refreshMarkers()
    }

    fun redo() {
        markerModel?.redo()
        refreshMarkers()
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying()) p.pause() else p.play()
        _uiState.value = _uiState.value.copy(isPlaying = p.isPlaying())
    }

    fun pause() {
        player?.pause()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    fun seekToFrame(frame: Int) {
        val clamped = frame.coerceIn(0, totalFrames)
        player?.seek(clamped)
        positionFrames = clamped
        updateAddDisabled()
    }

    fun seekNext() {
        markerModel?.let { seekToFrame(it.seekNext(positionFrames)) }
    }

    fun seekPrevious() {
        markerModel?.let { seekToFrame(it.seekPrevious(positionFrames)) }
    }

    private fun refreshMarkers() {
        val model = markerModel
        markerInfos = model?.markerItems?.filter { it.placed }?.map {
            OratureMarkerInfo(verseIndex = it.id, location = it.frame, label = it.label, movable = true)
        }?.sortedBy { it.location } ?: emptyList()
        val canUndo = model?.canUndo() ?: false
        val canRedo = model?.canRedo() ?: false
        _uiState.value = _uiState.value.copy(canUndo = canUndo, canRedo = canRedo, markers = markerInfos)
        translationVm.updateChunkUndoRedo(canUndo, canRedo)
        updateAddDisabled()
    }

    private fun updateAddDisabled() {
        val tooClose = markerInfos.any { kotlin.math.abs(it.location - positionFrames) < CHUNK_MIN_GAP_FRAMES }
        if (_uiState.value.addDisabled != tooClose) {
            _uiState.value = _uiState.value.copy(addDisabled = tooClose)
        }
    }

    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = viewModelScope.launch(Dispatchers.Default) {
            val out = FloatArray(CHUNK_WAVEFORM_WIDTH * 2)
            while (isActive) {
                val p = player
                val peaks = precomputed
                if (p != null && peaks != null) {
                    runCatching {
                        val playing = p.isPlaying()
                        if (playing) {
                            positionFrames = p.getLocationInFrames()
                            updateAddDisabled()
                        }
                        if (_uiState.value.isPlaying != playing) {
                            _uiState.value = _uiState.value.copy(isPlaying = playing)
                        }
                        // Slice the visible window from the in-memory peaks (µs) — no per-tick decode.
                        val halfWindow = CHUNK_SECONDS_ON_SCREEN * sampleRate / 2
                        peaks.window(positionFrames - halfWindow, out)
                        waveformFront = out.copyOf()
                    }.onFailure { System.err.println("[chunking] waveform render failed: $it") }
                }
                delay(33)
            }
        }
    }

    /** Persist on leaving the step (called from the screen's onDispose, since a keyed viewModel
     * isn't cleared when its step-branch merely leaves composition). */
    fun saveIfDirty() = save()

    /** Persist the placed chunks (JVM: saveChanges) — reset, recreate content, write chunked audio. */
    private fun save() {
        val wb = workbook ?: return
        val chap = chapter ?: return
        val src = sourceFile ?: return
        val model = markerModel ?: return
        if (!model.canUndo()) return // nothing changed
        val cues = model.markerItems.filter { it.placed }.map { it.toAudioCue() }
        val accessor = wb.projectFilesAccessor
        saveScope.launch {
            runCatching {
                resetChunks.resetChapter(accessor, chap)
                    .andThen(createChunks.createUserDefinedChunks(wb, chap, cues))
                    .await()
                ChunkAudioUseCase(directoryProvider, accessor).createChunkedSourceAudio(src, cues)
            }.onFailure { System.err.println("[chunking] save failed: $it") }
        }
    }

    public override fun onCleared() {
        translationVm.clearUndoRedoHandlers()
        save() // persist on leaving the step (JVM: undock)
        waveformTickerJob?.cancel()
        runCatching { player?.pause() }
        runCatching { player?.release() }
        player = null
        precomputed = null
        markerModel = null
    }

    companion object {
        private const val PLAYER_ID = 90_002
    }
}
