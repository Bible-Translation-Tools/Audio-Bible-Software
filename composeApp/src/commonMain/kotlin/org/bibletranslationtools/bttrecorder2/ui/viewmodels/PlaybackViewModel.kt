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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.bttrecorder2.ui.playback.CutAwareAudioFileReader
import org.bibletranslationtools.bttrecorder2.ui.playback.PlaybackWaveformRenderer
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
        val sourceLabel: String = "",
        val bookLabel: String = "",
        val chapterValue: String = "",
        val unitValue: String = "",
        val canGoPreviousChapter: Boolean = false,
        val canGoNextChapter: Boolean = false,
        val canGoPreviousUnit: Boolean = false,
        val canGoNextUnit: Boolean = false
    )

    data class PlaybackUiState(
        val targetUi: TargetUiState = TargetUiState(),
        val takes: List<Take> = emptyList(),
        val selectedTake: Take? = null,
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
        val showMinimap: Boolean = true,
        val sourceAudioAvailable: Boolean = false,
        val isEditMode: Boolean = false,
        val selectionStartProgress: Float? = null,
        val selectionEndProgress: Float? = null,
        val canCutSelection: Boolean = false,
        val canUndoEdit: Boolean = false,
        val canRedoEdit: Boolean = false,
        val hasEdits: Boolean = false,
        // Product decision: edited output defaults to a new take.
        val saveEditsAsNewTakeDefault: Boolean = true,
        val error: String? = null
    )

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

    private val playerId = Random.nextInt()
    private val audioPlayer: IAudioPlayer = AudioPlayerConnection(
        id = playerId,
        factory = audioPlayerFactory,
        scope = viewModelScope,
        controlDispatcher = Dispatchers.Default
    )

    private var workbook: Workbook? = null
    private var targets: List<PlaybackTarget> = emptyList()
    private var currentTargetIndex = -1
    private var associatedAudio: AssociatedAudio? = null
    private var requestedTakeNumber: Int? = null

    private var takesJob: Job? = null
    private var selectedJob: Job? = null
    private var tickerJob: Job? = null
    private var waveformRenderJob: Job? = null

    private var waveformWidth: Int = 0
    private var waveformRenderer: PlaybackWaveformRenderer? = null
    private var waveformSampleRate: Int = 44100
    private var markerFrames: List<Int> = emptyList()
    private var baseMarkers: List<AudioMarker> = emptyList()
    private var activeTake: Take? = null

    private var editSession: WaveEditSession? = null
    private var isEditMode: Boolean = false
    private var selectionStartFrame: Int? = null
    private var selectionEndFrame: Int? = null

    init {
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
        audioPlayer.seek(frame)
        refreshTransport()
        refreshWaveform()
    }

    fun seekToPreviousCue() {
        val current = audioPlayer.getLocationInFrames()
        val epsilon = 300
        val fallback = max(current - (5 * 44100), 0)
        val target = markerFrames.lastOrNull { it < current - epsilon } ?: fallback
        audioPlayer.seek(target)
        refreshTransport()
        refreshWaveform()
    }

    fun seekToStart() {
        audioPlayer.seek(0)
        refreshTransport()
        refreshWaveform()
    }

    fun seekToNextCue() {
        val current = audioPlayer.getLocationInFrames()
        val duration = audioPlayer.getDurationInFrames()
        val fallback = (current + (5 * 44100)).coerceAtMost(duration)
        val target = markerFrames.firstOrNull { it > current + 300 } ?: fallback
        audioPlayer.seek(target)
        refreshTransport()
        refreshWaveform()
    }

    fun goPreviousChapter() {
        if (_uiState.value.isPlaying) return
        val current = currentTarget() ?: return
        val chunkSort = current.chunk?.sort
        val prevChapter = targets.map { it.chapter.sort }.distinct().sorted().lastOrNull { it < current.chapter.sort }
            ?: return
        val idx = targets.indexOfFirst {
            it.chapter.sort == prevChapter && (
                if (chunkSort == null) it.chunk == null else it.chunk?.sort == chunkSort
                )
        }.let { if (it >= 0) it else targets.indexOfFirst { t -> t.chapter.sort == prevChapter && t.chunk == null } }
        if (idx >= 0) switchToTarget(idx)
    }

    fun goNextChapter() {
        if (_uiState.value.isPlaying) return
        val current = currentTarget() ?: return
        val chunkSort = current.chunk?.sort
        val nextChapter = targets.map { it.chapter.sort }.distinct().sorted().firstOrNull { it > current.chapter.sort }
            ?: return
        val idx = targets.indexOfFirst {
            it.chapter.sort == nextChapter && (
                if (chunkSort == null) it.chunk == null else it.chunk?.sort == chunkSort
                )
        }.let { if (it >= 0) it else targets.indexOfFirst { t -> t.chapter.sort == nextChapter && t.chunk == null } }
        if (idx >= 0) switchToTarget(idx)
    }

    fun goPreviousUnit() {
        if (_uiState.value.isPlaying) return
        val current = currentTarget() ?: return
        val currentChunk = current.chunk ?: return
        val unitsInChapter = targets.withIndex()
            .filter { it.value.chapter.sort == current.chapter.sort && it.value.chunk != null }
            .sortedBy { it.value.chunk?.sort }
        val pos = unitsInChapter.indexOfFirst { it.value.chunk?.sort == currentChunk.sort }
        if (pos > 0) switchToTarget(unitsInChapter[pos - 1].index)
    }

    fun goNextUnit() {
        if (_uiState.value.isPlaying) return
        val current = currentTarget() ?: return
        val currentChunk = current.chunk ?: return
        val unitsInChapter = targets.withIndex()
            .filter { it.value.chapter.sort == current.chapter.sort && it.value.chunk != null }
            .sortedBy { it.value.chunk?.sort }
        val pos = unitsInChapter.indexOfFirst { it.value.chunk?.sort == currentChunk.sort }
        if (pos >= 0 && pos < unitsInChapter.lastIndex) switchToTarget(unitsInChapter[pos + 1].index)
    }

    fun selectPreviousTake() {
        val takes = _uiState.value.takes
        val selected = _uiState.value.selectedTake ?: return
        if (takes.isEmpty()) return
        val idx = takes.indexOfFirst { it.number == selected.number }
        if (idx <= 0) return
        associatedAudio?.selectTake(takes[idx - 1])
    }

    fun selectNextTake() {
        val takes = _uiState.value.takes
        val selected = _uiState.value.selectedTake ?: return
        if (takes.isEmpty()) return
        val idx = takes.indexOfFirst { it.number == selected.number }
        if (idx < 0 || idx >= takes.lastIndex) return
        associatedAudio?.selectTake(takes[idx + 1])
    }

    fun showMinimap(show: Boolean) {
        _uiState.value = _uiState.value.copy(showMinimap = show)
    }

    fun startEditing() {
        if (_uiState.value.selectedTake == null) return
        isEditMode = true
        updateEditUi()
    }

    fun finishEditing() {
        isEditMode = false
        clearSelectionInternal()
        updateEditUi()
    }

    fun discardEdits() {
        val take = activeTake ?: return
        val current = audioPlayer.getLocationInFrames()
        createFreshEditSession(take)
        isEditMode = false
        clearSelectionInternal()
        reloadCurrentTakePlayback(current.coerceAtMost(editSession?.editedTotalFrames ?: current))
    }

    fun markSelectionStartAtCurrent() {
        if (!isEditMode) return
        selectionStartFrame = audioPlayer.getLocationInFrames()
        updateEditUi()
    }

    fun markSelectionEndAtCurrent() {
        if (!isEditMode) return
        selectionEndFrame = audioPlayer.getLocationInFrames()
        updateEditUi()
    }

    fun clearSelection() {
        clearSelectionInternal()
        updateEditUi()
    }

    fun cutSelection() {
        if (!isEditMode) return
        val start = selectionStartFrame ?: return
        val end = selectionEndFrame ?: return
        val session = editSession ?: return
        if (abs(end - start) < 2) return
        val seekTo = min(start, end)
        if (session.cutRelative(start, end)) {
            clearSelectionInternal()
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

    /**
     * Persists edited audio as a new take.
     * Product default: never overwrite an existing take with edit output.
     */
    fun saveEditedAudioAsNewTake(editedAudioFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                persistEditedFileAsNewTake(editedAudioFile)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to save edited take"
                )
            }
        }
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
        markerFrames = emptyList()
        baseMarkers = emptyList()
        activeTake = null
        editSession = null
        isEditMode = false
        clearSelectionInternal()

        _uiState.value = _uiState.value.copy(
            selectedTake = null,
            takes = emptyList(),
            waveformSamples = floatArrayOf(),
            progress = 0f,
            currentFrame = 0,
            durationFrames = 0,
            sampleRate = 44100,
            markerFrames = emptyList(),
            elapsedText = "00:00:00",
            durationText = "00:00:00",
            error = null
        )

        updateTargetUi(target, wb)
        updateEditUi()
        observeTargetAudio()
    }

    private fun updateTargetUi(target: PlaybackTarget, wb: Workbook) {
        val chapterSorts = targets.map { it.chapter.sort }.distinct().sorted()
        val chunkSorts = targets
            .mapNotNull { if (it.chapter.sort == target.chapter.sort) it.chunk?.sort else null }
            .distinct()
            .sorted()
        val canNavigate = !_uiState.value.isPlaying
        _uiState.value = _uiState.value.copy(
            targetUi = TargetUiState(
                sourceLabel = wb.source.resourceMetadata.identifier.uppercase(),
                bookLabel = wb.target.label,
                chapterValue = target.chapter.sort.toString(),
                unitValue = (target.chunk?.sort ?: 0).toString(),
                canGoPreviousChapter = canNavigate && chapterSorts.any { it < target.chapter.sort },
                canGoNextChapter = canNavigate && chapterSorts.any { it > target.chapter.sort },
                canGoPreviousUnit = canNavigate && target.chunk != null && chunkSorts.any { it < target.chunk.sort },
                canGoNextUnit = canNavigate && target.chunk != null && chunkSorts.any { it > target.chunk.sort }
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
            isEditMode = false
            clearSelectionInternal()
            reloadCurrentTakePlayback(0)
        }.onFailure { e ->
            _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load take")
        }
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
        markerFrames = mapEditedMarkers(baseMarkers)
            .filterIsInstance<VerseMarker>()
            .map { it.location }
            .sorted()
        _uiState.value = _uiState.value.copy(markerFrames = markerFrames)
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

    private fun clearSelectionInternal() {
        selectionStartFrame = null
        selectionEndFrame = null
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
            isEditMode = isEditMode,
            selectionStartProgress = startProgress,
            selectionEndProgress = endProgress,
            canCutSelection = canCut,
            canUndoEdit = editSession?.canUndo() == true,
            canRedoEdit = editSession?.canRedo() == true,
            hasEdits = editSession?.hasEdits() == true
        )
    }

    private fun refreshWaveform() {
        val renderer = waveformRenderer ?: return
        val frame = audioPlayer.getLocationInFrames()
        if (waveformRenderJob?.isActive == true) return
        waveformRenderJob = viewModelScope.launch(Dispatchers.Default) {
            val samples = renderer.renderCentered(frame)
            _uiState.update { state ->
                state.copy(waveformSamples = samples)
            }
        }
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
        waveformRenderJob?.cancel()
        waveformRenderJob = null
    }

    private fun refreshTransport() {
        val durationMs = audioPlayer.getDurationMs().coerceAtLeast(0)
        val positionMs = audioPlayer.getLocationMs().coerceIn(0, durationMs)
        val durationFrames = audioPlayer.getDurationInFrames().coerceAtLeast(0)
        val currentFrame = audioPlayer.getLocationInFrames().coerceIn(0, durationFrames)
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

        _uiState.value = _uiState.value.copy(
            progress = progress,
            currentFrame = currentFrame,
            durationFrames = durationFrames,
            sampleRate = waveformSampleRate,
            elapsedMs = positionMs,
            durationMs = durationMs,
            elapsedText = formatTime(positionMs),
            durationText = formatTime(durationMs)
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
        waveformRenderJob?.cancel()
        waveformRenderJob = null
        waveformRenderer?.close()
        waveformRenderer = null
    }

    fun cleanup() {
        stopTicker()
        takesJob?.cancel()
        selectedJob?.cancel()
        closeWaveformRenderer()
        audioPlayer.release()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
