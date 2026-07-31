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
import org.bibletranslationtools.orature.services.OratureWorkbookDataStore
import org.bibletranslationtools.shared.audio.engine.AudioTimeline
import org.bibletranslationtools.shared.audio.engine.FilePcmSource
import org.bibletranslationtools.shared.audio.engine.PcmSource
import org.bibletranslationtools.shared.audio.engine.WaveformPeakCache
import org.bibletranslationtools.shared.audio.engine.buildPeakCache
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.audio.ChunkMarker
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.AudioFileReader
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.shared.audio.engine.PlaybackDisplayClock
import org.bibletranslationtools.otter.common.domain.content.CreateChunks
import org.bibletranslationtools.otter.common.domain.content.ResetChunks
import org.bibletranslationtools.otter.common.domain.model.DEFAULT_CHUNK_MARKER_TOTAL
import org.bibletranslationtools.otter.common.domain.model.MarkerItem
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementModel
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementType
import org.bibletranslationtools.orature.ui.translation.ChunkingStep
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
 * chunks via [ResetChunks] + [CreateChunks] + [ChunkAudioUseCase]. [translationVm] is updated so the
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
    private val resetChunks: ResetChunks by inject()

    private val _uiState = MutableStateFlow(OratureChunkingUiState())
    val uiState: StateFlow<OratureChunkingUiState> = _uiState.asStateFlow()

    private var player: IAudioPlayer? = null
    // Shared waveform engine (see OratureChapterReviewViewModel).
    private var timeline: AudioTimeline? = null
    private var peakCache: WaveformPeakCache? = null
    private var peakSource: PcmSource? = null
    private var peakBuildJob: Job? = null
    // Rate-locked display clock (see OratureChapterReviewViewModel) for smooth waveform scroll.
    val clock = PlaybackDisplayClock(
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
    // Save must outlive the VM (it's triggered as the VM is torn down on navigate).
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // True when marker state has changed since the last successful save. The step-leave save
    // (awaited saveSuspend) clears this, so the onCleared backstop does NOT re-run the destructive
    // reset+insert on a detached scope during app-close (which, if interrupted mid-delete, wiped
    // the already-committed chunks — the "persists then vanishes on restart" bug).
    private var dirty = false

    fun currentTimeline(): AudioTimeline? = timeline
    fun peakCacheFor(source: PcmSource): WaveformPeakCache? =
        if (source.id == peakSource?.id) peakCache else null
    fun waveformSampleRate(): Int = sampleRate
    fun currentPosition(): Int = positionFrames
    fun currentTotalFrames(): Int = totalFrames
    fun currentMarkers(): List<OratureMarkerInfo> = markerInfos

    init {
        // The header undo/redo route here while this step is active (JVM: translationViewModel).
        translationVm.setUndoRedoHandlers(::undo, ::redo)
        // The translation VM awaits this before leaving the step, so the chunk DB writes commit
        // (and are readable) BEFORE Blind Draft loads — matching Orature's synchronous undock save.
        translationVm.setChunkSaveHandler(::saveSuspend)
        load()
    }

    /** Persist the chunks and WAIT for completion (JVM: saveChanges().blockingAwait() in undock). */
    private suspend fun saveSuspend() {
        val wb = workbook ?: return
        val chap = chapter ?: return
        val src = sourceFile ?: return
        val model = markerModel ?: return
        if (!model.canUndo()) return // nothing changed
        val cues = model.markerItems.filter { it.placed }.map { it.toAudioCue() }
        val accessor = wb.projectFilesAccessor
        withContext(Dispatchers.IO) {
            runCatching {
                resetChunks.resetChapter(accessor, chap)
                    .andThen(createChunks.createUserDefinedChunks(wb, chap, cues))
                    .await()
                ChunkAudioUseCase(directoryProvider, accessor).createChunkedSourceAudio(src, cues)
                dirty = false
            }.onFailure { System.err.println("Chunk save failed: $it") }
        }
    }

    private fun load() {
        launchLogged {
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
                    // Single-segment timeline + empty peak cache for the shared renderer (filled
                    // off-thread below); the draw samples it per pixel, no per-tick decode.
                    val source = FilePcmSource(file)
                    val tl = AudioTimeline.ofWholeSource(source)
                    val cache = WaveformPeakCache(source.totalFrames)
                    Prepared(wb, chap, file, playerReader, sr, source, tl, cache, model)
                }

                workbook = prepared.workbook
                chapter = prepared.chapter
                sourceFile = prepared.file
                markerModel = prepared.model
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
        val source: PcmSource,
        val timeline: AudioTimeline,
        val cache: WaveformPeakCache,
        val model: MarkerPlacementModel
    )

    fun placeMarker() {
        val model = markerModel ?: return
        model.addMarker(positionFrames)
        dirty = true
        refreshMarkers()
    }

    fun moveMarker(id: Int, newFrame: Int) {
        val model = markerModel ?: return
        val original = model.markerItems.firstOrNull { it.id == id }?.frame ?: return
        model.moveMarker(id, original, newFrame.coerceIn(0, totalFrames))
        dirty = true
        refreshMarkers()
    }

    fun deleteMarker(id: Int) {
        markerModel?.deleteMarker(id)
        dirty = true
        refreshMarkers()
    }

    fun undo() {
        markerModel?.undo()
        dirty = true
        refreshMarkers()
    }

    fun redo() {
        markerModel?.redo()
        dirty = true
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
                    }.onFailure { System.err.println("[chunking] player state poll failed: $it") }
                }
                delay(33)
            }
        }
    }

    /** Backstop persist for the "quit / leave the whole page while still on the Chunking step" case,
     *  where the awaited step-leave save never ran. Only fires when there are genuinely unsaved edits
     *  ([dirty]) so it never re-runs the destructive reset+insert over already-committed chunks. */
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
            }.onFailure { System.err.println("Chunk backstop save failed: $it") }
        }
    }

    public override fun onCleared() {
        translationVm.clearUndoRedoHandlers()
        translationVm.clearChunkSaveHandler()
        // Only save if there are unsaved edits AND we're being torn down because the user moved
        // FORWARD past Chunking — the JVM's `undock()` condition
        // (`selectedStep.ordinal > CHUNKING.ordinal`). The save is destructive (resetChapter deletes
        // this chapter's takes), so tearing down any other way — cancelling the re-chunk warning and
        // going back, closing the chapter — must leave the takes and the committed chunks alone.
        // After a step-leave save (awaited saveSuspend) dirty is already false, so this never re-runs
        // the reset+insert over already-committed chunks.
        val movedPastChunking =
            translationVm.uiState.value.selectedStep.ordinal > ChunkingStep.CHUNKING.ordinal
        if (dirty && movedPastChunking) save()
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
