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
import kotlinx.coroutines.withContext
import org.bibletranslationtools.shared.audio.engine.AudioTimeline
import org.bibletranslationtools.shared.audio.engine.FilePcmSource
import org.bibletranslationtools.shared.audio.engine.PcmSource
import org.bibletranslationtools.shared.audio.engine.WaveformPeakCache
import org.bibletranslationtools.shared.audio.engine.buildPeakCache
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.audio.MarkerType
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.IAudioPlayer
import org.bibletranslationtools.shared.audio.engine.PlaybackDisplayClock
import org.bibletranslationtools.otter.common.domain.IUndoable
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.model.MarkerItem
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementModel
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementType
import org.bibletranslationtools.otter.common.domain.model.UndoableActionHistory
import org.bibletranslationtools.otter.common.domain.translation.AddMarkerAction
import org.bibletranslationtools.otter.common.domain.translation.DeleteMarkerAction
import org.bibletranslationtools.otter.common.domain.translation.MoveMarkerAction
import org.bibletranslationtools.orature.services.OratureVerseMarkerEditor
import org.bibletranslationtools.orature.services.OratureVerseText
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


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
    // Shared waveform engine (see OratureChapterReviewViewModel).
    private var timeline: AudioTimeline? = null
    private var peakCache: WaveformPeakCache? = null
    private var peakSource: PcmSource? = null
    private var peakBuildJob: Job? = null
    // Rate-locked display clock (see OratureChapterReviewViewModel) for smooth waveform scroll.
    val clock = PlaybackDisplayClock(
        positionSource = { takePlayer?.getLocationInFrames()?.toLong() ?: 0L },
        positionReliable = { takePlayer?.isPositionReliable() ?: false }
    )
    private var clockEventsJob: Job? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var totalFrames: Int = 0
    private var positionFrames: Int = 0
    private var markerInfos: List<OratureMarkerInfo> = emptyList()
    private var waveformTickerJob: Job? = null

    private val actionHistory = UndoableActionHistory<IUndoable>()

    fun currentTimeline(): AudioTimeline? = timeline
    fun peakCacheFor(source: PcmSource): WaveformPeakCache? =
        if (source.id == peakSource?.id) peakCache else null
    fun waveformSampleRate(): Int = sampleRate
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
        launchLogged {
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
                    // Single-segment timeline + empty peak cache for the shared renderer (filled
                    // off-thread below); the draw samples it per pixel, no per-tick decode.
                    val source = FilePcmSource(req.takeFile)
                    val tl = AudioTimeline.ofWholeSource(source)
                    val cache = WaveformPeakCache(source.totalFrames)
                    Prepared(model, playerReader.totalFrames, sr, source, tl, cache)
                }

                markerModel = prepared.model
                sampleRate = prepared.sampleRate
                totalFrames = prepared.totalFrames
                positionFrames = 0
                timeline = prepared.timeline
                peakCache = prepared.cache
                peakSource = prepared.source
                peakBuildJob?.cancel()
                peakBuildJob = launchLogged(Dispatchers.IO) {
                    runCatching { buildPeakCache(prepared.source, prepared.cache) }
                }

                val p = AudioPlayerConnection(TAKE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
                p.load(OratureAudioFile(req.takeFile).reader().apply { open() })
                takePlayer = p
                clock.sampleRate = sampleRate
                clock.durationFrames = totalFrames.toLong()
                clock.advancing = false
                clock.snapTo(0L)
                observePlayerForClock(p)

                refreshMarkers()
                _uiState.value = _uiState.value.copy(isLoading = false)
                startWaveformTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading the verse marker screen", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    private data class Prepared(
        val model: MarkerPlacementModel,
        val totalFrames: Int,
        val sampleRate: Int,
        val source: PcmSource,
        val timeline: AudioTimeline,
        val cache: WaveformPeakCache
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
        clock.snapTo(clamped.toLong())
        updateHighlight()
    }

    /** Drive the display clock from the take player's transport events (main thread). */
    private fun observePlayerForClock(p: IAudioPlayer) {
        clockEventsJob?.cancel()
        clockEventsJob = launchLogged {
            p.events.collect { e ->
                when (e) {
                    AudioPlayerEvent.Play -> clock.advancing = true
                    AudioPlayerEvent.Pause -> clock.advancing = false
                    AudioPlayerEvent.Stop -> { clock.advancing = false; clock.snapTo(clock.displayFrame) }
                    AudioPlayerEvent.Complete -> { clock.advancing = false; clock.snapTo(clock.durationFrames) }
                    is AudioPlayerEvent.Error -> clock.advancing = false
                    else -> Unit
                }
            }
        }
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
        launchLogged {
            stopPlayback()
            withContext(Dispatchers.IO) { writeMarkersBlocking() }
            runCatching { request?.onSaved?.invoke() }
                .onFailure { logFailure("reloading the host screen after writing verse markers", it) }
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

    /** Polls the take player for position + play/pause state (and the highlighted verse). The
     *  waveform is drawn by the shared renderer sampling the peak cache in the draw pass. */
    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = launchLogged(Dispatchers.Default) {
            while (isActive) {
                val p = takePlayer
                if (p != null) {
                    runCatching {
                        val playing = p.isPlaying()
                        if (playing) {
                            positionFrames = p.getLocationInFrames()
                            updateHighlight()
                        }
                        if (_uiState.value.isPlaying != playing) {
                            _uiState.value = _uiState.value.copy(isPlaying = playing)
                        }
                    }.onFailure { logFailure("polling take state on the verse marker screen", it) }
                }
                delay(33)
            }
        }
    }

    private fun writeMarkersBlocking() {
        val model = markerModel ?: return
        runCatching { model.writeMarkers().blockingAwait() }
            .onFailure { logFailure("saving verse markers", it) }
    }

    private fun stopPlayback() {
        waveformTickerJob?.cancel()
        peakBuildJob?.cancel()
        clockEventsJob?.cancel()
        clock.advancing = false
        runCatching { takePlayer?.pause() }
        runCatching { takePlayer?.release() }
        takePlayer = null
        timeline = null
        peakCache = null
        peakSource = null
    }

    public override fun onCleared() {
        stopPlayback()
        markerModel = null
    }

    companion object {
        private const val TAKE_PLAYER_ID = 90_040
    }
}
