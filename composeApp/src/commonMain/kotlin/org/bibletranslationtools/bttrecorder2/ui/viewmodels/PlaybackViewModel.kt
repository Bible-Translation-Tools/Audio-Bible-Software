package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.bttrecorder2.ui.playback.CutAwareAudioFileReader
import org.bibletranslationtools.bttrecorder2.ui.playback.MinimapWaveformRenderer
import org.bibletranslationtools.bttrecorder2.ui.playback.PlaybackWaveformRenderer
import org.bibletranslationtools.bttrecorder2.ui.playback.SourceAudioPlayerController
import org.bibletranslationtools.bttrecorder2.ui.playback.WaveEditSession
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.AudioBouncer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.Recordable
import org.bibletranslationtools.otter.common.domain.content.TakeCreator
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class PlaybackViewModel(
    private val workbookRepository: IWorkbookRepository,
    private val audioPlayerFactory: AudioPlayerConnectionFactory,
    private val takeCreator: TakeCreator,
    private val audioBouncer: AudioBouncer
) : ViewModel() {

    data class TargetUiState(
        val languageLabel: String = "",
        val projectLabel: String = "",
        val bookLabel: String = "",
        val chapterValue: String = "",
        val unitValue: String = ""
    )

    data class PlaybackUiState(
        val targetUi: TargetUiState = TargetUiState(),
        val takes: List<Take> = emptyList(),
        val selectedTake: Take? = null,
        val currentTakeLabel: String = "",
        val isPlaying: Boolean = false,
        val progress: Float = 0f,
        val currentFrame: Int = 0,
        val durationFrames: Int = 0,
        val sampleRate: Int = 44100,
        val elapsedMs: Int = 0,
        val durationMs: Int = 0,
        val elapsedText: String = "00:00:00",
        val durationText: String = "00:00:00",
        val waveformSamples: FloatArray = floatArrayOf(),
        val markerFrames: List<Int> = emptyList(),
        val markerLabels: List<String> = emptyList(),
        val minimapSamples: FloatArray = floatArrayOf(),
        val showMinimap: Boolean = true,
        val sourceAudioAvailable: Boolean = false,
        val selectionStartProgress: Float? = null,
        val selectionEndProgress: Float? = null,
        val canCutSelection: Boolean = false,
        val canUndoEdit: Boolean = false,
        val canRedoEdit: Boolean = false,
        val hasEdits: Boolean = false,
        val isVerseMarkerMode: Boolean = false,
        val versesMarked: Int = 0,
        val error: String? = null
    )

    sealed class NavEvent {
        data class Rerecord(
            val sourceId: Int,
            val targetId: Int,
            val chapterNumber: Int,
            val unitNumber: Int
        ) : NavEvent()
        data class Insert(
            val sourceId: Int,
            val targetId: Int,
            val chapterNumber: Int,
            val unitNumber: Int
        ) : NavEvent()
    }

    private data class PlaybackTarget(
        val chapter: Chapter,
        val chunk: Chunk?
    ) {
        val recordable: Recordable
            get() = chunk ?: chapter
    }

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _editedTakeSavedEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val editedTakeSavedEvents: SharedFlow<Int> = _editedTakeSavedEvents.asSharedFlow()

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    private val playerId = Random.nextInt()
    private val audioPlayer: IAudioPlayer = AudioPlayerConnection(
        id = playerId,
        factory = audioPlayerFactory,
        scope = viewModelScope,
        controlDispatcher = Dispatchers.Default
    )

    private val sourceAudioController = SourceAudioPlayerController(
        factory = audioPlayerFactory,
        scope = viewModelScope
    )

    val sourceAudioState: StateFlow<SourceAudioPlayerController.UiState> = sourceAudioController.uiState

    private var workbook: Workbook? = null
    private var targets: List<PlaybackTarget> = emptyList()
    private var currentTargetIndex = -1
    private var associatedAudio: AssociatedAudio? = null
    private var requestedTakeNumber: Int? = null

    private var takesJob: Job? = null
    private var selectedJob: Job? = null
    private var tickerJob: Job? = null
    private var minimapRenderJob: Job? = null

    // Desired waveform center frame. collectLatest ensures each new value cancels
    // any in-progress render so the latest drag position always wins.
    private val _desiredWaveformFrame = MutableStateFlow(0)

    private var waveformWidth: Int = 0
    private var minimapWidth: Int = 0
    private var waveformRenderer: PlaybackWaveformRenderer? = null
    private var minimapRenderer: MinimapWaveformRenderer? = null
    private var waveformSampleRate: Int = 44100
    private var markerFrames: List<Int> = emptyList()
    private var markerLabels: List<String> = emptyList()
    private var baseMarkers: List<AudioMarker> = emptyList()
    private var activeTake: Take? = null

    private var editSession: WaveEditSession? = null
    private var selectionStartFrame: Int? = null
    private var selectionEndFrame: Int? = null

    private val droppedVerseMarkerFrames = mutableListOf<Int>()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            _desiredWaveformFrame.collectLatest { frame ->
                val renderer = waveformRenderer ?: return@collectLatest
                val samples = renderer.renderCentered(frame)
                _uiState.update { it.copy(waveformSamples = samples) }
            }
        }

        viewModelScope.launch {
            audioPlayer.events.collect { event ->
                when (event) {
                    AudioPlayerEvent.Play -> {
                        _uiState.value = _uiState.value.copy(isPlaying = true, error = null)
                        startTicker()
                    }

                    AudioPlayerEvent.Pause,
                    AudioPlayerEvent.Stop,
                    AudioPlayerEvent.Complete -> {
                        _uiState.value = _uiState.value.copy(isPlaying = false)
                        stopTicker()
                        refreshTransport()
                        refreshWaveform()
                    }

                    is AudioPlayerEvent.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isPlaying = false,
                            error = event.message
                        )
                        stopTicker()
                    }

                    else -> Unit
                }
            }
        }
    }

    fun loadTarget(
        sourceId: Int,
        targetId: Int,
        chapterNumber: Int,
        unitNumber: Int,
        takeNumber: Int?
    ) {
        requestedTakeNumber = takeNumber
        viewModelScope.launch(Dispatchers.IO) {
            val projects = workbookRepository.getProjectsSuspend()
            val foundWorkbook = projects.find {
                it.source.collectionId == sourceId && it.target.collectionId == targetId
            } ?: return@launch

            val chapterList = foundWorkbook.target.chapters.toList().await()
                .sortedBy { it.sort }
            if (chapterList.isEmpty()) return@launch

            val expandedTargets = mutableListOf<PlaybackTarget>()
            chapterList.forEach { chapter ->
                expandedTargets.add(PlaybackTarget(chapter = chapter, chunk = null))
                chapter.chunksSuspend().sortedBy { it.sort }.forEach { chunk ->
                    expandedTargets.add(PlaybackTarget(chapter = chapter, chunk = chunk))
                }
            }

            val desiredUnit = if (unitNumber == -1) null else unitNumber
            val initialIndex = expandedTargets.indexOfFirst { target ->
                if (target.chapter.sort != chapterNumber) return@indexOfFirst false
                if (desiredUnit == null) target.chunk == null else target.chunk?.sort == desiredUnit
            }

            workbook = foundWorkbook
            targets = expandedTargets
            switchToTarget(if (initialIndex >= 0) initialIndex else 0, force = true)
        }
    }

    fun setWaveformWidth(width: Int) {
        if (width <= 0 || width == waveformWidth) return
        waveformWidth = width
        activeTake?.let {
            setupWaveformRenderer(it)
            refreshWaveform()
        }
    }

    fun setMinimapWidth(width: Int) {
        if (width <= 0 || width == minimapWidth) return
        minimapWidth = width
        activeTake?.let { loadMinimapSamples(it) }
    }

    fun togglePlayPause() {
        if (_uiState.value.selectedTake == null) return
        if (_uiState.value.isPlaying) {
            _uiState.value = _uiState.value.copy(isPlaying = false)
            stopTicker()
            audioPlayer.pause()
        } else {
            _uiState.value = _uiState.value.copy(isPlaying = true, error = null)
            startTicker()
            audioPlayer.play()
        }
    }

    fun seekToProgress(progress: Float) {
        val duration = audioPlayer.getDurationInFrames()
        if (duration <= 0) return
        val frame = (duration * progress.coerceIn(0f, 1f)).toInt()
        println("seekToProgress: progress=$progress, targetFrame=$frame, duration=$duration")
        audioPlayer.seek(frame)
        refreshTransport()
        refreshWaveform()
    }

    fun seekBackward() {
        val current = audioPlayer.getLocationInFrames()
        val target = max(current - (5 * waveformSampleRate), 0)
        audioPlayer.seek(target)
        refreshTransport()
        refreshWaveform()
    }

    fun seekForward() {
        val current = audioPlayer.getLocationInFrames()
        val duration = audioPlayer.getDurationInFrames()
        val target = min(current + (5 * waveformSampleRate), duration)
        audioPlayer.seek(target)
        refreshTransport()
        refreshWaveform()
    }

    fun seekToFrame(absoluteFrame: Int) {
        val duration = audioPlayer.getDurationInFrames()
        if (duration <= 0) return
        val target = absoluteFrame.coerceIn(0, duration)
        audioPlayer.seek(target)
        // Update currentFrame synchronously so the Canvas uses the correct center immediately
        // when dragAccumPx resets on drag-end, before the async waveform render completes.
        _uiState.update { it.copy(
            currentFrame = target,
            progress = target.toFloat() / duration.toFloat()
        ) }
        refreshTransport()
        _desiredWaveformFrame.value = target
    }

    fun seekByFrameDelta(deltaFrames: Int) {
        seekToFrame(audioPlayer.getLocationInFrames() + deltaFrames)
    }

    fun showMinimap(show: Boolean) {
        _uiState.value = _uiState.value.copy(showMinimap = show)
    }

    fun markSelectionStartAtCurrent() {
        selectionStartFrame = audioPlayer.getLocationInFrames()
        // Reset end mark if start moved past it
        val end = selectionEndFrame
        if (end != null && selectionStartFrame != null && end <= selectionStartFrame!!) {
            selectionEndFrame = null
        }
        updateEditUi()
    }

    fun markSelectionEndAtCurrent() {
        val start = selectionStartFrame ?: return
        val current = audioPlayer.getLocationInFrames()
        if (current != start) {
            selectionEndFrame = current
            updateEditUi()
        }
    }

    fun clearSelection() {
        selectionStartFrame = null
        selectionEndFrame = null
        updateEditUi()
    }

    fun cutSelection() {
        val start = selectionStartFrame ?: return
        val end = selectionEndFrame ?: return
        val session = editSession ?: return
        if (abs(end - start) < 2) return
        val seekTo = min(start, end)
        if (session.cutRelative(start, end)) {
            selectionStartFrame = null
            selectionEndFrame = null
            reloadCurrentTakePlayback(seekTo.coerceAtMost(session.editedTotalFrames))
        }
    }

    fun undoEdit() {
        val session = editSession ?: return
        if (!session.undo()) return
        val seekTo = audioPlayer.getLocationInFrames().coerceAtMost(session.editedTotalFrames)
        reloadCurrentTakePlayback(seekTo)
    }

    fun redoEdit() {
        val session = editSession ?: return
        if (!session.redo()) return
        val seekTo = audioPlayer.getLocationInFrames().coerceAtMost(session.editedTotalFrames)
        reloadCurrentTakePlayback(seekTo)
    }

    fun saveCurrentEditsAsNewTake() {
        val take = activeTake ?: return
        val session = editSession ?: return
        if (!session.hasEdits()) {
            _uiState.value = _uiState.value.copy(error = "No edits to save")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tempEditedWav = File.createTempFile("edited_", ".wav")
                val reader = buildReaderForTake(take)
                val markers = mapEditedMarkers(baseMarkers)
                audioBouncer.bounceAudio(tempEditedWav, reader, markers)
                persistEditedFileAsNewTake(tempEditedWav)
                tempEditedWav.delete()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to save edited take"
                )
            }
        }
    }

    fun enterVerseMarkerMode() {
        if (_uiState.value.selectedTake == null) return
        droppedVerseMarkerFrames.clear()
        _uiState.value = _uiState.value.copy(
            isVerseMarkerMode = true,
            versesMarked = 0
        )
    }

    fun exitVerseMarkerMode() {
        droppedVerseMarkerFrames.clear()
        _uiState.value = _uiState.value.copy(
            isVerseMarkerMode = false,
            versesMarked = 0
        )
    }

    fun dropVerseMarkerAtCurrentPosition() {
        val frame = audioPlayer.getLocationInFrames()
        droppedVerseMarkerFrames.add(frame)
        val allMarkerFrames = (markerFrames + droppedVerseMarkerFrames).distinct().sorted()
        val allMarkerLabels = buildMarkerLabelsForFrames(allMarkerFrames)
        _uiState.value = _uiState.value.copy(
            versesMarked = droppedVerseMarkerFrames.size,
            markerFrames = allMarkerFrames,
            markerLabels = allMarkerLabels
        )
    }

    fun saveVerseMarkersAsNewTake() {
        if (droppedVerseMarkerFrames.isEmpty()) {
            exitVerseMarkerMode()
            return
        }
        val take = activeTake ?: return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tempWav = File.createTempFile("marked_", ".wav")
                val reader = buildReaderForTake(take)
                val allMarkerFrames = (markerFrames + droppedVerseMarkerFrames).distinct().sorted()
                val newMarkers = allMarkerFrames.mapIndexed { idx, frame ->
                    VerseMarker(start = idx + 1, end = idx + 1, location = frame)
                }
                audioBouncer.bounceAudio(tempWav, reader, newMarkers)
                persistEditedFileAsNewTake(tempWav)
                tempWav.delete()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to save verse markers"
                )
            }
        }
        exitVerseMarkerMode()
    }

    fun onRerecord() {
        val wb = workbook ?: return
        val target = currentTarget() ?: return
        _navEvents.tryEmit(
            NavEvent.Rerecord(
                sourceId = wb.source.collectionId,
                targetId = wb.target.collectionId,
                chapterNumber = target.chapter.sort,
                unitNumber = target.chunk?.sort ?: 0
            )
        )
    }

    fun onInsert() {
        val wb = workbook ?: return
        val target = currentTarget() ?: return
        _navEvents.tryEmit(
            NavEvent.Insert(
                sourceId = wb.source.collectionId,
                targetId = wb.target.collectionId,
                chapterNumber = target.chapter.sort,
                unitNumber = target.chunk?.sort ?: 0
            )
        )
    }

    private suspend fun persistEditedFileAsNewTake(editedAudioFile: File) {
        val wb = workbook ?: return
        val target = currentTarget() ?: return
        val audio = associatedAudio ?: return

        if (!editedAudioFile.exists()) {
            throw IllegalStateException("Edited audio file does not exist")
        }

        val newTakeNumber = audio.getNewTakeNumberSuspend()
        val namer = WorkbookFileNamerBuilder.createFileNamer(
            workbook = wb,
            chapter = target.chapter,
            chunk = target.chunk,
            recordable = target.recordable,
            rcSlug = wb.sourceMetadataSlug
        )

        val filename = namer.generateName(newTakeNumber, AudioFileFormat.WAV)
        val takeDir = wb.projectFilesAccessor.getChapterAudioDir(wb, target.chapter)
        val newTake = takeCreator.createNewTake(
            newTakeNumber = newTakeNumber,
            filename = filename,
            audioDir = takeDir,
            createEmpty = false
        )

        editedAudioFile.copyTo(newTake.file, overwrite = true)
        audio.insertTake(newTake)
        audio.selectTake(newTake)
        _editedTakeSavedEvents.tryEmit(newTake.number)
    }

    private fun currentTarget(): PlaybackTarget? = targets.getOrNull(currentTargetIndex)

    private fun switchToTarget(index: Int, force: Boolean = false) {
        if (index !in targets.indices) return
        if (!force && _uiState.value.isPlaying) return

        currentTargetIndex = index
        val wb = workbook ?: return
        val target = targets[index]
        associatedAudio = target.recordable.audio

        audioPlayer.pause()
        stopTicker()
        closeWaveformRenderer()
        closeMinimapRenderer()
        markerFrames = emptyList()
        markerLabels = emptyList()
        baseMarkers = emptyList()
        activeTake = null
        editSession = null
        selectionStartFrame = null
        selectionEndFrame = null
        droppedVerseMarkerFrames.clear()

        _uiState.value = _uiState.value.copy(
            selectedTake = null,
            takes = emptyList(),
            currentTakeLabel = "",
            waveformSamples = floatArrayOf(),
            minimapSamples = floatArrayOf(),
            progress = 0f,
            currentFrame = 0,
            durationFrames = 0,
            sampleRate = 44100,
            markerFrames = emptyList(),
            markerLabels = emptyList(),
            elapsedText = "00:00:00",
            durationText = "00:00:00",
            isVerseMarkerMode = false,
            versesMarked = 0,
            error = null
        )

        updateTargetUi(target, wb)
        updateEditUi()
        observeTargetAudio()

        // Resolve and load source audio for this target. Disk-bound work goes off
        // the main thread; the controller's state flow drives the UI.
        viewModelScope.launch(Dispatchers.IO) {
            val available = sourceAudioController.load(wb, target.chapter, target.chunk)
            _uiState.update { it.copy(sourceAudioAvailable = available) }
        }
    }

    fun toggleSourcePlayback() {
        sourceAudioController.togglePlayPause()
    }

    fun seekSourceToProgress(progress: Float) {
        sourceAudioController.seekToProgress(progress)
    }

    private fun updateTargetUi(target: PlaybackTarget, wb: Workbook) {
        _uiState.value = _uiState.value.copy(
            targetUi = TargetUiState(
                languageLabel = wb.target.language.name,
                projectLabel = wb.target.resourceMetadata.identifier.uppercase(),
                bookLabel = wb.target.label,
                chapterValue = target.chapter.sort.toString(),
                unitValue = (target.chunk?.sort ?: 0).toString()
            )
        )
    }

    private fun observeTargetAudio() {
        takesJob?.cancel()
        selectedJob?.cancel()

        val audio = associatedAudio ?: return
        val takeMap = linkedMapOf<Int, Take>()

        takesJob = viewModelScope.launch {
            audio.takesFlow.collect { take ->
                takeMap[take.number] = take
                val takes = takeMap.values
                    .filter { !it.isDeleted() }
                    .sortedBy { it.number }
                _uiState.value = _uiState.value.copy(takes = takes)

                val selected = _uiState.value.selectedTake
                if (selected == null || selected.isDeleted()) {
                    val requested = requestedTakeNumber?.let { req -> takes.find { it.number == req } }
                    val fallback = requested ?: takes.maxByOrNull { it.number }
                    if (fallback != null) {
                        audio.selectTake(fallback)
                        requestedTakeNumber = null
                    }
                }
            }
        }

        selectedJob = viewModelScope.launch {
            audio.selectedFlow.collect { selectedHolder ->
                val selectedTake = selectedHolder.value
                _uiState.value = _uiState.value.copy(selectedTake = selectedTake)
                if (selectedTake != null && !selectedTake.isDeleted()) {
                    loadTakeForPlayback(selectedTake)
                }
            }
        }
    }

    private fun loadTakeForPlayback(take: Take) {
        runCatching {
            activeTake = take
            createFreshEditSession(take)
            val originalAudio = OratureAudioFile(take.file)
            baseMarkers = originalAudio.getMarkers().sortedBy { it.location }
            markerFrames = baseMarkers.filterIsInstance<VerseMarker>().map { it.location }
            markerLabels = baseMarkers.filterIsInstance<VerseMarker>().map { it.label }
            droppedVerseMarkerFrames.clear()
            selectionStartFrame = null
            selectionEndFrame = null
            reloadCurrentTakePlayback(0)
        }.onFailure { e ->
            _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load take")
        }
    }

    private fun buildMarkerLabelsForFrames(frames: List<Int>): List<String> {
        // Reconstruct labels by matching existing base markers, then numbering new ones
        val existingByFrame = baseMarkers.filterIsInstance<VerseMarker>()
            .associate { it.location to it.label }
        return frames.map { frame -> existingByFrame[frame] ?: "+" }
    }

    private fun createFreshEditSession(take: Take) {
        val totalFrames = OratureAudioFile(take.file).totalFrames
        editSession = WaveEditSession(totalFrames)
    }

    private fun buildReaderForTake(take: Take): AudioFileReader {
        val baseReader = OratureAudioFile(take.file).reader()
        val ranges = editSession?.rangesSnapshot().orEmpty()
        return if (ranges.isEmpty()) {
            baseReader
        } else {
            CutAwareAudioFileReader(baseReader, ranges)
        }
    }

    private fun mapEditedMarkers(markers: List<AudioMarker>): List<AudioMarker> {
        val session = editSession ?: return markers
        if (!session.hasEdits()) return markers

        return markers.mapNotNull { marker ->
            if (session.isFrameRemoved(marker.location)) {
                null
            } else {
                marker.clone(session.absoluteToRelative(marker.location))
            }
        }.sortedBy { it.location }
    }

    private fun refreshMarkerFrames() {
        val editedMarkers = mapEditedMarkers(baseMarkers).filterIsInstance<VerseMarker>()
        markerFrames = editedMarkers.map { it.location }.sorted()
        markerLabels = editedMarkers.sortedBy { it.location }.map { it.label }
        _uiState.value = _uiState.value.copy(
            markerFrames = markerFrames,
            markerLabels = markerLabels
        )
    }

    private fun reloadCurrentTakePlayback(seekFrame: Int) {
        val take = activeTake ?: return
        audioPlayer.pause()
        stopTicker()

        val reader = buildReaderForTake(take)
        waveformSampleRate = reader.spec.sampleRate
        val durationFrames = reader.totalFrames
        val clampedSeek = seekFrame.coerceIn(0, durationFrames)

        audioPlayer.load(reader)
        audioPlayer.seek(clampedSeek)

        setupWaveformRenderer(take)
        loadMinimapSamples(take)
        refreshMarkerFrames()
        refreshTransport()
        refreshWaveform()
        updateEditUi()
    }

    private fun setupWaveformRenderer(take: Take) {
        closeWaveformRenderer()
        if (waveformWidth <= 0) return
        val reader = buildReaderForTake(take)
        waveformSampleRate = reader.spec.sampleRate
        reader.open()
        waveformRenderer = PlaybackWaveformRenderer(
            reader = reader,
            width = waveformWidth,
            secondsOnScreen = 10
        )
    }

    private fun loadMinimapSamples(take: Take) {
        closeMinimapRenderer()
        if (minimapWidth <= 0) return
        minimapRenderJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val reader = buildReaderForTake(take)
                reader.open()
                val renderer = MinimapWaveformRenderer(reader = reader, width = minimapWidth)
                minimapRenderer = renderer
                val samples = renderer.render()
                _uiState.update { it.copy(minimapSamples = samples) }
            }
        }
    }

    private fun updateEditUi() {
        val duration = audioPlayer.getDurationInFrames().coerceAtLeast(1)
        val startProgress = selectionStartFrame
            ?.coerceIn(0, duration)
            ?.let { it.toFloat() / duration.toFloat() }
        val endProgress = selectionEndFrame
            ?.coerceIn(0, duration)
            ?.let { it.toFloat() / duration.toFloat() }
        val canCut = selectionStartFrame != null &&
            selectionEndFrame != null &&
            abs((selectionStartFrame ?: 0) - (selectionEndFrame ?: 0)) >= 2

        _uiState.value = _uiState.value.copy(
            selectionStartProgress = startProgress,
            selectionEndProgress = endProgress,
            canCutSelection = canCut,
            canUndoEdit = editSession?.canUndo() == true,
            canRedoEdit = editSession?.canRedo() == true,
            hasEdits = editSession?.hasEdits() == true
        )
    }

    private fun refreshWaveform() {
        _desiredWaveformFrame.value = audioPlayer.getLocationInFrames()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (_uiState.value.isPlaying) {
                refreshTransport()
                refreshWaveform()
                delay(33)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun refreshTransport() {
        val durationMs = audioPlayer.getDurationMs().coerceAtLeast(0)
        val positionMs = audioPlayer.getLocationMs().coerceIn(0, durationMs)
        val durationFrames = audioPlayer.getDurationInFrames().coerceAtLeast(0)
        val currentFrame = audioPlayer.getLocationInFrames().coerceIn(0, durationFrames)
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        val take = _uiState.value.selectedTake

        _uiState.value = _uiState.value.copy(
            progress = progress,
            currentFrame = currentFrame,
            durationFrames = durationFrames,
            sampleRate = waveformSampleRate,
            elapsedMs = positionMs,
            durationMs = durationMs,
            elapsedText = formatTime(positionMs),
            durationText = formatTime(durationMs),
            currentTakeLabel = take?.let { "Take ${it.number}" } ?: ""
        )
        updateEditUi()
    }

    private fun formatTime(ms: Int): String {
        val seconds = ms / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun closeWaveformRenderer() {
        waveformRenderer?.close()
        waveformRenderer = null
    }

    private fun closeMinimapRenderer() {
        minimapRenderJob?.cancel()
        minimapRenderJob = null
        minimapRenderer?.close()
        minimapRenderer = null
    }

    fun cleanup() {
        stopTicker()
        takesJob?.cancel()
        selectedJob?.cancel()
        closeWaveformRenderer()
        closeMinimapRenderer()
        sourceAudioController.release()
        audioPlayer.release()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
