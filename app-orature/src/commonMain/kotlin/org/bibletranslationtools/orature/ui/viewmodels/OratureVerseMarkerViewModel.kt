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
import org.bibletranslationtools.orature.ui.workbook.PrecomputedWaveform
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.audio.MarkerType
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.IUndoable
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.model.MarkerItem
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementModel
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementType
import org.bibletranslationtools.otter.common.domain.model.UndoableActionHistory
import org.bibletranslationtools.otter.common.domain.translation.AddMarkerAction
import org.bibletranslationtools.otter.common.domain.translation.DeleteMarkerAction
import org.bibletranslationtools.otter.common.domain.translation.MoveMarkerAction
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val MARKER_WAVEFORM_WIDTH = 960
private const val MARKER_SECONDS_ON_SCREEN = 10

/** UI state for the built-in Verse Marker editor (JVM: `VerseMarkerViewModel`). */
data class OratureVerseMarkerUiState(
    val isLoading: Boolean = true,
    /** True once a request has been consumed and the take loaded; false when there is nothing to edit. */
    val hasContent: Boolean = false,
    val actionTitle: String = "",
    val contentTitle: String = "",
    val isPlaying: Boolean = false,
    /** Placed markers on the take, sorted by location. */
    val markers: List<OratureMarkerInfo> = emptyList(),
    /** "placed/total" ratio text (JVM: `markerRatioProperty`). */
    val placedCount: Int = 0,
    val totalCount: Int = 0,
    /** Index of the highlighted marker among the highlightable (verse) markers, or -1. */
    val highlightedIndex: Int = -1,
    val sourceText: List<OratureVerseText> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val error: String? = null
)

/**
 * Drives the built-in Verse Marker editor (JVM: `VerseMarkerViewModel` + `MarkerView`). Loads the
 * compiled chapter take handed off via [OratureVerseMarkerEditor], shows it as a scrolling waveform
 * with editable verse markers (add at playhead / move / delete, all undoable — JVM `IMarkerViewModel`),
 * and on save writes the cues back into the take and invokes the host reload.
 *
 * The marker engine is the shared [MarkerPlacementModel] (VERSE); playback + waveform reuse the
 * Final Review path (`OratureChapterReviewViewModel`), which edits verse markers on the same
 * compiled-take representation.
 */
class OratureVerseMarkerViewModel : ViewModel(), KoinComponent {

    private val editor: OratureVerseMarkerEditor by inject()
    private val playerFactory: AudioPlayerConnectionFactory by inject()

    private val _uiState = MutableStateFlow(OratureVerseMarkerUiState())
    val uiState: StateFlow<OratureVerseMarkerUiState> = _uiState.asStateFlow()

    private var request: OratureVerseMarkerEditor.Request? = null
    private var markerModel: MarkerPlacementModel? = null
    private var takePlayer: IAudioPlayer? = null
    private var precomputed: PrecomputedWaveform? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var totalFrames: Int = 0
    private var positionFrames: Int = 0
    private var markerInfos: List<OratureMarkerInfo> = emptyList()
    private var waveformFront: FloatArray = FloatArray(MARKER_WAVEFORM_WIDTH * 2)
    private var waveformTickerJob: Job? = null

    private val actionHistory = UndoableActionHistory<IUndoable>()

    fun currentWaveform(): FloatArray = waveformFront
    fun currentPosition(): Int = positionFrames
    fun currentTotalFrames(): Int = totalFrames
    fun currentMarkers(): List<OratureMarkerInfo> = markerInfos

    init {
        val req = editor.request.value
        if (req == null) {
            _uiState.value = OratureVerseMarkerUiState(isLoading = false, hasContent = false)
        } else {
            request = req
            _uiState.value = OratureVerseMarkerUiState(
                isLoading = true,
                hasContent = true,
                actionTitle = req.actionTitle,
                contentTitle = req.contentTitle,
                sourceText = req.sourceText
            )
            load(req)
        }
    }

    private fun load(req: OratureVerseMarkerEditor.Request) {
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val takeAudio = OratureAudioFile(req.takeFile)
                    // Reserved set = the full verse+title marker set; placed = those already written
                    // into the take (JVM: loadMarkersFromParameters + loadVerseMarkers). Mirrors
                    // OratureChapterReviewViewModel.loadChapterTake exactly.
                    val model = MarkerPlacementModel(
                        MarkerPlacementType.VERSE,
                        takeAudio,
                        req.reservedMarkers.map { it.clone(0) }
                    ).apply {
                        loadMarkers(takeAudio.getVerseAndTitleMarkers().map { MarkerItem(it, true) })
                    }

                    val playerReader = takeAudio.reader().apply { open() }
                    val sr = playerReader.spec.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    val peakReader = takeAudio.reader().apply { open() }
                    val peaks = try {
                        PrecomputedWaveform.build(peakReader, MARKER_WAVEFORM_WIDTH, MARKER_SECONDS_ON_SCREEN, sr)
                    } finally {
                        runCatching { peakReader.release() }
                    }
                    Prepared(model, playerReader.totalFrames, sr, peaks)
                }

                markerModel = prepared.model
                sampleRate = prepared.sampleRate
                totalFrames = prepared.totalFrames
                positionFrames = 0
                precomputed = prepared.peaks

                val p = AudioPlayerConnection(TAKE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
                p.load(OratureAudioFile(req.takeFile).reader().apply { open() })
                takePlayer = p

                refreshMarkers()
                _uiState.value = _uiState.value.copy(isLoading = false)
                startWaveformTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    private data class Prepared(
        val model: MarkerPlacementModel,
        val totalFrames: Int,
        val sampleRate: Int,
        val peaks: PrecomputedWaveform
    )

    fun togglePlay() {
        val p = takePlayer ?: return
        if (p.isPlaying()) p.pause() else p.play()
        _uiState.value = _uiState.value.copy(isPlaying = p.isPlaying())
    }

    fun pause() {
        takePlayer?.pause()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    fun seekToFrame(frame: Int) {
        val clamped = frame.coerceIn(0, totalFrames)
        takePlayer?.seek(clamped)
        positionFrames = clamped
        updateHighlight()
    }

    fun seekNext() {
        markerModel?.let { seekToFrame(it.seekNext(positionFrames)) }
    }

    fun seekPrevious() {
        markerModel?.let { seekToFrame(it.seekPrevious(positionFrames)) }
    }

    /** Add the next unplaced verse marker at the playhead (JVM: placeMarker → AddMarkerAction). */
    fun placeMarker() {
        val model = markerModel ?: return
        actionHistory.execute(AddMarkerAction(model, positionFrames))
        refreshMarkers()
    }

    fun moveMarker(id: Int, newFrame: Int) {
        val model = markerModel ?: return
        val original = model.markerItems.firstOrNull { it.id == id }?.frame ?: return
        actionHistory.execute(MoveMarkerAction(model, id, original, newFrame.coerceIn(0, totalFrames)))
        refreshMarkers()
    }

    fun deleteMarker(id: Int) {
        val model = markerModel ?: return
        actionHistory.execute(DeleteMarkerAction(model, id))
        refreshMarkers()
    }

    fun undo() {
        if (!actionHistory.canUndo()) return
        actionHistory.undo()
        refreshMarkers()
    }

    fun redo() {
        if (!actionHistory.canRedo()) return
        actionHistory.redo()
        refreshMarkers()
    }

    /** Write the placed cues back into the take, run the host reload, then clear the handoff. */
    fun saveAndClose(onClosed: () -> Unit) {
        viewModelScope.launch {
            stopPlayback()
            withContext(Dispatchers.IO) { writeMarkersBlocking() }
            runCatching { request?.onSaved?.invoke() }
                .onFailure { System.err.println("[verse-marker] host reload failed: $it") }
            editor.close()
            onClosed()
        }
    }

    /** Discard: close without writing (the take keeps its previous cues). */
    fun cancel(onClosed: () -> Unit) {
        stopPlayback()
        editor.close()
        onClosed()
    }

    private fun refreshMarkers() {
        val model = markerModel
        markerInfos = model?.markerItems?.filter { it.placed }?.map {
            OratureMarkerInfo(verseIndex = it.id, location = it.frame, label = it.label, movable = true)
        }?.sortedBy { it.location } ?: emptyList()
        _uiState.value = _uiState.value.copy(
            markers = markerInfos,
            placedCount = markerInfos.size,
            totalCount = model?.markerTotal ?: 0,
            canUndo = actionHistory.canUndo(),
            canRedo = actionHistory.canRedo()
        )
        updateHighlight()
    }

    /**
     * Highlight the verse marker the playhead currently sits in, expressed as an index among the
     * verse (CONTENT) markers so the source-text panel can highlight the matching row (JVM:
     * `IMarkerViewModel.updateHighlightedIndex` = absolute index − count of non-highlightable
     * title markers, which sort first).
     */
    private fun updateHighlight() {
        val model = markerModel ?: return
        val placed = model.markerItems.filter { it.placed }.sortedBy { it.frame }
        val currentFrame = model.seekCurrent(positionFrames)
        val idx = placed.indexOfFirst { it.frame == currentFrame }.takeIf { it >= 0 } ?: 0
        val titleCount = placed.count { it.marker.type == MarkerType.TITLE }
        val highlighted = (idx - titleCount).coerceAtLeast(-1)
        if (_uiState.value.highlightedIndex != highlighted) {
            _uiState.value = _uiState.value.copy(highlightedIndex = highlighted)
        }
    }

    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = viewModelScope.launch(Dispatchers.Default) {
            val out = FloatArray(MARKER_WAVEFORM_WIDTH * 2)
            while (isActive) {
                val p = takePlayer
                val peaks = precomputed
                if (p != null && peaks != null) {
                    runCatching {
                        val playing = p.isPlaying()
                        if (playing) {
                            positionFrames = p.getLocationInFrames()
                            updateHighlight()
                        }
                        if (_uiState.value.isPlaying != playing) {
                            _uiState.value = _uiState.value.copy(isPlaying = playing)
                        }
                        val halfWindow = MARKER_SECONDS_ON_SCREEN * sampleRate / 2
                        peaks.window(positionFrames - halfWindow, out)
                        waveformFront = out.copyOf()
                    }.onFailure { System.err.println("[verse-marker] waveform render failed: $it") }
                }
                delay(33)
            }
        }
    }

    private fun writeMarkersBlocking() {
        val model = markerModel ?: return
        runCatching { model.writeMarkers().blockingAwait() }
            .onFailure { System.err.println("[verse-marker] marker save failed: $it") }
    }

    private fun stopPlayback() {
        waveformTickerJob?.cancel()
        runCatching { takePlayer?.pause() }
        runCatching { takePlayer?.release() }
        takePlayer = null
        precomputed = null
    }

    public override fun onCleared() {
        stopPlayback()
        markerModel = null
    }

    companion object {
        private const val TAKE_PLAYER_ID = 90_040
    }
}
