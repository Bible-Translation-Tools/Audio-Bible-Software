package org.bibletranslationtools.orature.ui.viewmodels

import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.errUnknown
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.services.OratureSaveMessages
import org.bibletranslationtools.orature.services.OratureWorkbookDataStore
import org.bibletranslationtools.shared.audio.engine.AudioTimeline
import org.bibletranslationtools.shared.audio.engine.FilePcmSource
import org.bibletranslationtools.shared.audio.engine.PcmSource
import org.bibletranslationtools.shared.audio.engine.WaveformPeakCache
import org.bibletranslationtools.shared.audio.engine.buildPeakCache
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.audio.ChunkMarker
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.DraftId
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.AudioFileReader
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.shared.audio.engine.PlaybackDisplayPosition
import org.bibletranslationtools.otter.common.domain.content.CreateChunks
import org.bibletranslationtools.otter.common.domain.content.RechunkAction
import org.bibletranslationtools.otter.common.domain.content.RechunkChapter
import org.bibletranslationtools.otter.common.domain.model.DEFAULT_CHUNK_MARKER_TOTAL
import org.bibletranslationtools.otter.common.domain.model.MarkerItem
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementModel
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementType
import org.bibletranslationtools.otter.common.domain.translation.ChunkAudioUseCase
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

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
 * chunks via [RechunkAction]. [translationVm] is updated so the
 * page header's undo/redo reflect this step (JVM: chunking VM writes translationViewModel).
 */
class OratureChunkingViewModel(
    private val chapterSort: Int,
    private val translationVm: OratureTranslationViewModel
) : ViewModel(), KoinComponent {

    private val workbookDataStore: OratureWorkbookDataStore by inject()
    private val playerFactory: AudioPlayerConnectionFactory by inject()
    private val directoryProvider: ITempFileProvider by inject()
    private val createChunks: CreateChunks by inject()
    private val saveMessages: OratureSaveMessages by inject()
    private val rechunkChapter by lazy { RechunkChapter(createChunks, directoryProvider) }

    private val _uiState = MutableStateFlow(OratureChunkingUiState())
    val uiState: StateFlow<OratureChunkingUiState> = _uiState.asStateFlow()

    private var player: IAudioPlayer? = null
    // Shared waveform engine (see OratureChapterReviewViewModel).
    private var timeline: AudioTimeline? = null
    private var peakCache: WaveformPeakCache? = null
    private var peakSource: PcmSource? = null
    private var peakBuildJob: Job? = null
    // Rate-locked display clock (see OratureChapterReviewViewModel) for smooth waveform scroll.
    val clock = PlaybackDisplayPosition(
        positionSource = { player?.getLocationInFrames()?.toLong() ?: 0L },
        positionReliable = { player?.isPositionReliable() ?: false }
    )
    private var clockEventsJob: Job? = null
    private var markerModel: MarkerPlacementModel? = null

    private var workbook: Workbook? = null
    private var chapter: Chapter? = null
    private var sourceFile: File? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var totalFrames: Int = 0
    private var positionFrames: Int = 0
    private var markerInfos: List<OratureMarkerInfo> = emptyList()

    private var waveformTickerJob: Job? = null
    // The layout the markers were loaded from. A chapter undo or redo of a rechunk changes the
    // chapter's active layout, and the markers are reloaded to show it.
    private var loadedDraft: DraftId? = null
    // One save or reload at a time: a second step-leave while a rechunk saves must see its result.
    private val layoutLock = Mutex()

    fun currentTimeline(): AudioTimeline? = timeline
    fun peakCacheFor(source: PcmSource): WaveformPeakCache? =
        if (source.id == peakSource?.id) peakCache else null
    fun waveformSampleRate(): Int = sampleRate
    fun currentPosition(): Int = positionFrames
    fun currentTotalFrames(): Int = totalFrames
    fun currentMarkers(): List<OratureMarkerInfo> = markerInfos

    /**
     * Dock: the step's screen entered composition (the screen calls this from a DisposableEffect,
     * with [onUndock] on leaving). The step's ViewModel outlives the step, so it takes over the page
     * header's undo and redo here rather than in init. Its chunk-save handler is registered here too, so leaving the step
     * forward saves this chapter's markers.
     */
    fun onDock() {
        docked = true
        // The translation VM awaits this before leaving the step, so the chunk DB writes commit
        // (and are readable) BEFORE Blind Draft loads — matching Orature's synchronous undock save.
        translationVm.setChunkSaveHandler(::saveSuspend)
        // The header undo/redo route here while this step is active (JVM: translationViewModel).
        // After a chapter undo or redo, which may have switched the chapter's chunk layout, the
        // markers are reloaded.
        translationVm.setUndoRedoHandlers(
            ::undo,
            ::redo,
            afterChapterUndo = { launchLogged { layoutLock.withLock { reloadMarkersFromLayout() } } }
        )
        translationVm.updateChunkUndoRedo(
            canUndo = markerModel?.canUndo() ?: false,
            canRedo = markerModel?.canRedo() ?: false
        )
        // An undo or redo of a rechunk made from another step changed the active layout while this
        // step was not showing: show that layout's markers.
        val state = chapter?.state?.value
        if (state != null && state.loaded && state.activeDraft != loadedDraft) {
            launchLogged { layoutLock.withLock { reloadMarkersFromLayout() } }
        }
    }

    /** Undock: the step's screen left composition, so it no longer drives the page header. */
    fun onUndock() {
        docked = false
        translationVm.clearUndoRedoHandlers()
        translationVm.clearChunkSaveHandler()
    }

    /** Whether the step's screen is in composition; only then does it drive the page header. */
    private var docked = false

    init {
        load()
    }

    /**
     * Persist the chunks and WAIT for completion (JVM: saveChanges().blockingAwait() in undock).
     * The save is one [RechunkAction] in the chapter's undo history. Returns whether the layout is
     * saved (or there was nothing to save); a failure is shown to the user, and the caller must not
     * move on as if it saved.
     */
    private suspend fun saveSuspend(): Boolean = layoutLock.withLock {
        val wb = workbook ?: return@withLock true
        val chap = chapter ?: return@withLock true
        val src = sourceFile ?: return@withLock true
        val model = markerModel ?: return@withLock true
        if (!model.canUndo()) return@withLock true // nothing changed
        val cues = model.markerItems.filter { it.placed }.map { it.toAudioCue() }
        val result = saveMessages.report(chap.execute(RechunkAction(rechunkChapter, wb, chap, cues, src)))
        if (result !is SaveResult.Saved) return@withLock false
        // The saved layout's markers start a fresh in-memory history: undoing the rechunk itself is
        // the chapter's.
        reloadMarkersFromLayout()
        true
    }

    /**
     * Rebuilds the marker model from the chunk markers of the chapter's active layout, as written in
     * its source audio copy, dropping the in-memory marker edits and their undo history. Call with
     * [layoutLock] held.
     */
    private suspend fun reloadMarkersFromLayout() {
        val chap = chapter ?: return
        val file = sourceFile ?: return
        try {
            val draft = chap.loadedState().activeDraft
            val model = withContext(Dispatchers.IO) { markerModelFor(OratureAudioFile(file)) }
            markerModel = model
            loadedDraft = draft
            refreshMarkers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure("reloading the chunk markers", e)
        }
    }

    /** A chunk marker model loaded with [audioFile]'s chunk markers. */
    private fun markerModelFor(audioFile: OratureAudioFile): MarkerPlacementModel {
        audioFile.clearCues()
        val existing = audioFile.getMarker<ChunkMarker>().map { MarkerItem(it, true) }
        return MarkerPlacementModel(
            MarkerPlacementType.CHUNK,
            audioFile,
            (1..DEFAULT_CHUNK_MARKER_TOTAL).map { ChunkMarker(it, 0) }
        ).apply { loadMarkers(existing) }
    }

    private fun load() {
        launchLogged {
            _uiState.value = OratureChunkingUiState(isLoading = true)
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val wb = workbookDataStore.activeWorkbook.value ?: error("No active workbook")
                    val chap = wb.target.chapters().firstOrNull { it.sort == chapterSort }
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

                    // The layout the markers below belong to: a later undo or redo of a rechunk
                    // switches it, and the markers are reloaded then (onDock, afterChapterUndo).
                    val draft = chap.loadedState().activeDraft
                    val audioFile = OratureAudioFile(file)
                    val model = markerModelFor(audioFile)
                    val playerReader = audioFile.reader().apply { open() }
                    val sr = playerReader.spec.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    // Single-segment timeline + empty peak cache for the shared renderer (filled
                    // off-thread below); the draw samples it per pixel, no per-tick decode.
                    val source = FilePcmSource(file)
                    val tl = AudioTimeline.ofWholeSource(source)
                    val cache = WaveformPeakCache(source.totalFrames)
                    Prepared(wb, chap, file, playerReader, sr, source, tl, cache, model, draft)
                }

                workbook = prepared.workbook
                chapter = prepared.chapter
                sourceFile = prepared.file
                markerModel = prepared.model
                loadedDraft = prepared.draft
                sampleRate = prepared.sampleRate
                totalFrames = prepared.playerReader.totalFrames
                timeline = prepared.timeline
                peakCache = prepared.cache
                peakSource = prepared.source
                peakBuildJob?.cancel()
                peakBuildJob = launchLogged(Dispatchers.IO) {
                    runCatching { buildPeakCache(prepared.source, prepared.cache) }
                }
                val p = AudioPlayerConnection(PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
                p.load(prepared.playerReader)
                player = p
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
                logFailure("loading the chunking screen", e)
                _uiState.value = OratureChunkingUiState(isLoading = false, error = e.message ?: getString(Res.string.errUnknown))
            }
        }
    }

    private data class Prepared(
        val workbook: Workbook,
        val chapter: Chapter,
        val file: File,
        val playerReader: AudioFileReader,
        val sampleRate: Int,
        val source: PcmSource,
        val timeline: AudioTimeline,
        val cache: WaveformPeakCache,
        val model: MarkerPlacementModel,
        val draft: DraftId?
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
        clock.snapTo(clamped.toLong())
        updateAddDisabled()
    }

    /** Drive the display clock from the player's transport events (main thread). */
    private fun observePlayerForClock(p: IAudioPlayer) {
        clockEventsJob?.cancel()
        clockEventsJob = launchLogged {
            p.events.collect { e ->
                when (e) {
                    AudioPlayerEvent.Play -> clock.startAdvancing()
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

    private fun refreshMarkers() {
        val model = markerModel
        markerInfos = model?.markerItems?.filter { it.placed }?.map {
            OratureMarkerInfo(verseIndex = it.id, location = it.frame, label = it.label, movable = true)
        }?.sortedBy { it.location } ?: emptyList()
        val canUndo = model?.canUndo() ?: false
        val canRedo = model?.canRedo() ?: false
        _uiState.value = _uiState.value.copy(canUndo = canUndo, canRedo = canRedo, markers = markerInfos)
        if (docked) translationVm.updateChunkUndoRedo(canUndo, canRedo)
        updateAddDisabled()
    }

    private fun updateAddDisabled() {
        val tooClose = markerInfos.any { kotlin.math.abs(it.location - positionFrames) < CHUNK_MIN_GAP_FRAMES }
        if (_uiState.value.addDisabled != tooClose) {
            _uiState.value = _uiState.value.copy(addDisabled = tooClose)
        }
    }

    /** Polls the player for the playhead position + play/pause state (and the add-disabled gate). The
     *  waveform is drawn by the shared renderer sampling the peak cache in the draw pass — no per-tick
     *  window/allocation here anymore. */
    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = launchLogged(Dispatchers.Default) {
            while (isActive) {
                val p = player
                if (p != null) {
                    runCatching {
                        val playing = p.isPlaying()
                        if (playing) {
                            positionFrames = p.getLocationInFrames()
                            updateAddDisabled()
                        }
                        if (_uiState.value.isPlaying != playing) {
                            _uiState.value = _uiState.value.copy(isPlaying = playing)
                        }
                    }.onFailure { logFailure("polling player state on the chunking screen", it) }
                }
                delay(33)
            }
        }
    }

    public override fun onCleared() {
        translationVm.clearUndoRedoHandlers()
        translationVm.clearChunkSaveHandler()
        waveformTickerJob?.cancel()
        peakBuildJob?.cancel()
        clockEventsJob?.cancel()
        clock.advancing = false
        runCatching { player?.pause() }
        runCatching { player?.release() }
        player = null
        timeline = null
        peakCache = null
        peakSource = null
        markerModel = null
    }

    companion object {
        private const val PLAYER_ID = 90_002
    }
}
