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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.bttrecorder2.ui.playback.PlaybackWaveformRenderer
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.Recordable
import org.bibletranslationtools.otter.common.domain.content.TakeCreator
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import java.io.File
import kotlin.math.max
import kotlin.random.Random

class PlaybackViewModel(
    private val workbookRepository: IWorkbookRepository,
    private val audioPlayerFactory: AudioPlayerConnectionFactory,
    private val takeCreator: TakeCreator
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
        val elapsedText: String = "00:00:00",
        val durationText: String = "00:00:00",
        val waveformSamples: FloatArray = floatArrayOf(),
        val showMinimap: Boolean = true,
        val sourceAudioAvailable: Boolean = false,
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
        scope = viewModelScope
    )

    private var workbook: Workbook? = null
    private var targets: List<PlaybackTarget> = emptyList()
    private var currentTargetIndex = -1
    private var associatedAudio: AssociatedAudio? = null
    private var requestedTakeNumber: Int? = null

    private var takesJob: Job? = null
    private var selectedJob: Job? = null
    private var tickerJob: Job? = null

    private var waveformWidth: Int = 0
    private var waveformRenderer: PlaybackWaveformRenderer? = null
    private var markerFrames: List<Int> = emptyList()

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
                if (desiredUnit == null) {
                    target.chunk == null
                } else {
                    target.chunk?.sort == desiredUnit
                }
            }

            workbook = foundWorkbook
            targets = expandedTargets
            switchToTarget(if (initialIndex >= 0) initialIndex else 0, force = true)
        }
    }

    fun setWaveformWidth(width: Int) {
        if (width <= 0 || width == waveformWidth) return
        waveformWidth = width
        val selectedTake = _uiState.value.selectedTake ?: return
        setupWaveformRenderer(selectedTake)
        refreshWaveform()
    }

    fun togglePlayPause() {
        if (_uiState.value.selectedTake == null) return
        if (_uiState.value.isPlaying) {
            audioPlayer.pause()
        } else {
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

    /**
     * Persists edited audio as a new take.
     * Product default: never overwrite an existing take with edit output.
     */
    fun saveEditedAudioAsNewTake(editedAudioFile: File) {
        val wb = workbook ?: return
        val target = currentTarget() ?: return
        val audio = associatedAudio ?: return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
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
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to save edited take"
                )
            }
        }
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

        _uiState.value = _uiState.value.copy(
            selectedTake = null,
            takes = emptyList(),
            waveformSamples = floatArrayOf(),
            progress = 0f,
            elapsedText = "00:00:00",
            durationText = "00:00:00",
            error = null
        )

        updateTargetUi(target, wb)
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
            val audioFile = OratureAudioFile(take.file)
            val playerReader = audioFile.reader()
            playerReader.open()
            audioPlayer.load(playerReader)
            audioPlayer.seek(0)

            markerFrames = OratureAudioFile(take.file)
                .getMarker<VerseMarker>()
                .map { it.location }
                .sorted()

            setupWaveformRenderer(take)
            refreshTransport()
            refreshWaveform()
        }.onFailure { e ->
            _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load take")
        }
    }

    private fun setupWaveformRenderer(take: Take) {
        closeWaveformRenderer()
        if (waveformWidth <= 0) return
        val reader = OratureAudioFile(take.file).reader()
        reader.open()
        waveformRenderer = PlaybackWaveformRenderer(
            reader = reader,
            width = waveformWidth,
            secondsOnScreen = 10
        )
    }

    private fun refreshWaveform() {
        val renderer = waveformRenderer ?: return
        val frame = audioPlayer.getLocationInFrames()
        _uiState.value = _uiState.value.copy(
            waveformSamples = renderer.renderCentered(frame)
        )
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
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

        _uiState.value = _uiState.value.copy(
            progress = progress,
            elapsedText = formatTime(positionMs),
            durationText = formatTime(durationMs)
        )
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
