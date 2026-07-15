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
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.ui.workbook.OratureWorkbookDataStore
import org.bibletranslationtools.orature.ui.workbook.PrecomputedWaveform
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.ChapterTranslationBuilder
import org.bibletranslationtools.otter.common.domain.model.MarkerItem
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementModel
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementType
import org.bibletranslationtools.otter.common.domain.translation.AddMarkerAction
import org.bibletranslationtools.otter.common.domain.translation.DeleteMarkerAction
import org.bibletranslationtools.otter.common.domain.translation.MoveMarkerAction
import org.bibletranslationtools.otter.common.domain.model.UndoableActionHistory
import org.bibletranslationtools.otter.common.domain.IUndoable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val REVIEW_WAVEFORM_WIDTH = 960
private const val REVIEW_SECONDS_ON_SCREEN = 10

/** UI state for the Final Review step (JVM: `ChapterReviewViewModel`). */
data class OratureChapterReviewUiState(
    val isLoading: Boolean = true,
    val hasChapter: Boolean = false,
    val chapterTitle: String = "",
    val isSourcePlaying: Boolean = false,
    val isPlaying: Boolean = false,
    /** Placed verse markers on the compiled chapter take. */
    val markers: List<OratureMarkerInfo> = emptyList(),
    /** True when every required (source) marker is placed and there is a next chapter to go to. */
    val canGoNextChapter: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val error: String? = null
)

/**
 * Drives the Final Review step (JVM: `ChapterReviewViewModel`): compiles the chapter take from the
 * chunk takes, shows it as a waveform with editable verse markers (add at playhead / move / delete,
 * all undoable), plays source (top) and the compiled take (center), and advances to the next chapter
 * once all required markers are placed. Markers are written back to the take on leaving the step.
 *
 * Book/chapter optional markers are not editable here — the shared MarkerPlacementModel in this port
 * has no optional-marker action API (existing ones still load/save); verse markers are the focus.
 */
class OratureChapterReviewViewModel(
    private val translationVm: OratureTranslationViewModel
) : ViewModel(), KoinComponent {

    private val workbookDataStore: OratureWorkbookDataStore by inject()
    private val playerFactory: AudioPlayerConnectionFactory by inject()
    private val chapterTranslationBuilder: ChapterTranslationBuilder by inject()

    private val _uiState = MutableStateFlow(OratureChapterReviewUiState())
    val uiState: StateFlow<OratureChapterReviewUiState> = _uiState.asStateFlow()

    private var workbook: Workbook? = null
    private var chapter: Chapter? = null
    private var markerModel: MarkerPlacementModel? = null
    private var sourcePlayer: IAudioPlayer? = null
    private var takePlayer: IAudioPlayer? = null
    private var precomputed: PrecomputedWaveform? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var totalFrames: Int = 0
    private var positionFrames: Int = 0
    private var markerInfos: List<OratureMarkerInfo> = emptyList()
    private var waveformFront: FloatArray = FloatArray(REVIEW_WAVEFORM_WIDTH * 2)
    private var waveformTickerJob: Job? = null

    private val actionHistory = UndoableActionHistory<IUndoable>()

    fun currentWaveform(): FloatArray = waveformFront
    fun currentPosition(): Int = positionFrames
    fun currentTotalFrames(): Int = totalFrames
    fun currentMarkers(): List<OratureMarkerInfo> = markerInfos

    init {
        translationVm.setUndoRedoHandlers(::undo, ::redo)
        viewModelScope.launch {
            workbookDataStore.activeChapter.collect { chap -> onChapter(chap) }
        }
    }

    private fun onChapter(chap: Chapter?) {
        // Persist markers of the chapter we're leaving before loading another.
        writeMarkersBlocking()
        stopAll()
        actionHistory.clear()
        translationVm.updateChunkUndoRedo(canUndo = false, canRedo = false)
        chapter = chap
        if (chap == null) {
            _uiState.value = OratureChapterReviewUiState(hasChapter = false, isLoading = false)
            return
        }
        // Final Review is chapter-level; clear the active chunk so the source-text drawer shows the
        // whole chapter's text (JVM: dock sets activeChunkProperty = null).
        workbookDataStore.setActiveChunk(null)
        _uiState.value = OratureChapterReviewUiState(isLoading = true, hasChapter = true, chapterTitle = chap.title)
        loadChapterTake(chap)
    }

    private fun loadChapterTake(chap: Chapter) {
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val wb = workbookDataStore.activeWorkbook.value ?: error("No active workbook")
                    workbook = wb
                    // Compile (or reuse) the chapter take from the chunk takes (JVM: getOrCompile).
                    val take = chapterTranslationBuilder.getOrCompile(wb, chap).await()
                    val takeAudio = OratureAudioFile(take.file)

                    // Reserved marker set = source verse/title markers; placed markers = those already
                    // on the compiled take (JVM: loadVerseMarkers).
                    val sourceMarkers = sourceMarkers(wb, chap)
                    val model = MarkerPlacementModel(
                        MarkerPlacementType.VERSE,
                        takeAudio,
                        sourceMarkers.map { it.clone(0) }
                    ).apply {
                        loadMarkers(takeAudio.getVerseAndTitleMarkers().map { MarkerItem(it, true) })
                    }

                    val playerReader = takeAudio.reader().apply { open() }
                    val sr = playerReader.spec.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    val peakReader = takeAudio.reader().apply { open() }
                    val peaks = try {
                        PrecomputedWaveform.build(peakReader, REVIEW_WAVEFORM_WIDTH, REVIEW_SECONDS_ON_SCREEN, sr)
                    } finally {
                        runCatching { peakReader.release() }
                    }
                    Prepared(model, take.file, playerReader.totalFrames, sr, peaks, prepareSourcePlayer(wb, chap))
                }

                markerModel = prepared.model
                sampleRate = prepared.sampleRate
                totalFrames = prepared.totalFrames
                positionFrames = 0
                precomputed = prepared.peaks
                sourcePlayer = prepared.sourcePlayer

                val p = AudioPlayerConnection(TAKE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
                p.load(OratureAudioFile(prepared.takeFile).reader().apply { open() })
                takePlayer = p

                refreshMarkers()
                _uiState.value = _uiState.value.copy(isLoading = false, chapterTitle = chap.title)
                startWaveformTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = OratureChapterReviewUiState(
                    hasChapter = true, isLoading = false, error = e.message ?: "Unknown error"
                )
            }
        }
    }

    private data class Prepared(
        val model: MarkerPlacementModel,
        val takeFile: java.io.File,
        val totalFrames: Int,
        val sampleRate: Int,
        val peaks: PrecomputedWaveform,
        val sourcePlayer: IAudioPlayer?
    )

    /** Source verse/title markers used as the required marker set (JVM: getSourceMarkers). */
    private fun sourceMarkers(wb: Workbook, chap: Chapter): List<AudioMarker> {
        val sa = runCatching { wb.sourceAudioAccessor.getChapter(chap.sort, wb.target) }.getOrNull()
        return if (sa != null) {
            runCatching { OratureAudioFile(sa.file).getVerseAndTitleMarkers() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    private fun prepareSourcePlayer(wb: Workbook, chap: Chapter): IAudioPlayer? {
        val sa = runCatching {
            wb.sourceAudioAccessor.getUserMarkedChapter(chap.sort, wb.target)
                ?: wb.sourceAudioAccessor.getChapter(chap.sort, wb.target)
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

    fun seekNext() {
        markerModel?.let { seekToFrame(it.seekNext(positionFrames)) }
    }

    fun seekPrevious() {
        markerModel?.let { seekToFrame(it.seekPrevious(positionFrames)) }
    }

    /** Add a verse marker at the playhead (JVM: placeMarker → AddMarkerAction). */
    fun placeMarker() {
        val model = markerModel ?: return
        actionHistory.execute(AddMarkerAction(model, positionFrames))
        onUndoableAction()
        refreshMarkers()
    }

    fun moveMarker(id: Int, newFrame: Int) {
        val model = markerModel ?: return
        val original = model.markerItems.firstOrNull { it.id == id }?.frame ?: return
        actionHistory.execute(MoveMarkerAction(model, id, original, newFrame.coerceIn(0, totalFrames)))
        onUndoableAction()
        refreshMarkers()
    }

    fun deleteMarker(id: Int) {
        val model = markerModel ?: return
        actionHistory.execute(DeleteMarkerAction(model, id))
        onUndoableAction()
        refreshMarkers()
    }

    fun undo() {
        if (!actionHistory.canUndo()) return
        actionHistory.undo()
        translationVm.updateChunkUndoRedo(canUndo = actionHistory.canUndo(), canRedo = true)
        refreshMarkers()
    }

    fun redo() {
        if (!actionHistory.canRedo()) return
        actionHistory.redo()
        translationVm.updateChunkUndoRedo(canUndo = true, canRedo = actionHistory.canRedo())
        refreshMarkers()
    }

    private fun onUndoableAction() {
        translationVm.updateChunkUndoRedo(canUndo = true, canRedo = false)
    }

    /** Advance to the next chapter (JVM: GoToNextChapterEvent). Saves markers first. */
    fun goToNextChapter() {
        if (!_uiState.value.canGoNextChapter) return
        writeMarkersBlocking()
        translationVm.selectNextChapter()
    }

    private fun refreshMarkers() {
        val model = markerModel
        markerInfos = model?.markerItems?.filter { it.placed }?.map {
            OratureMarkerInfo(verseIndex = it.id, location = it.frame, label = it.label, movable = true)
        }?.sortedBy { it.location } ?: emptyList()
        val allPlaced = model?.markerItems?.all { it.placed } ?: false
        _uiState.value = _uiState.value.copy(
            markers = markerInfos,
            canUndo = actionHistory.canUndo(),
            canRedo = actionHistory.canRedo(),
            canGoNextChapter = allPlaced && translationVm.uiState.value.hasNextChapter
        )
    }

    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = viewModelScope.launch(Dispatchers.Default) {
            val out = FloatArray(REVIEW_WAVEFORM_WIDTH * 2)
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
                        val halfWindow = REVIEW_SECONDS_ON_SCREEN * sampleRate / 2
                        peaks.window(positionFrames - halfWindow, out)
                        waveformFront = out.copyOf()
                    }.onFailure { System.err.println("[review] waveform render failed: $it") }
                }
                delay(33)
            }
        }
    }

    /** Persist the placed verse markers back to the compiled chapter take (JVM: undock writeMarkers). */
    private fun writeMarkersBlocking() {
        val model = markerModel ?: return
        runCatching { model.writeMarkers().blockingAwait() }
            .onFailure { System.err.println("Chapter-review marker save failed: $it") }
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
    }

    public override fun onCleared() {
        translationVm.clearUndoRedoHandlers()
        writeMarkersBlocking()
        stopAll()
        markerModel = null
    }

    companion object {
        private const val SOURCE_PLAYER_ID = 90_030
        private const val TAKE_PLAYER_ID = 90_031
    }
}
