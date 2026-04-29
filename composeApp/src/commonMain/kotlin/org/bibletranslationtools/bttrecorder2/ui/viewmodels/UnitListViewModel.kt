package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bibletranslationtools.bttrecorder2.preferences.IAppPreferences
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import kotlinx.coroutines.flow.firstOrNull
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.data.workbook.Take
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import org.bibletranslationtools.otter.common.data.workbook.DateHolder
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerEvent
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first

data class UnitUiModel(
    val unit: Chunk,
    val hasContent: Boolean = false,
    val takes: Int = 0
)

data class UnitListUiState(
    val isLoading: Boolean = false,
    val units: List<UnitUiModel> = emptyList(),
    val chapter: Chapter? = null,
    val workbook: Workbook? = null,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val playbackProgress: Float = 0f,
    val currentPlayingTake: Take? = null,
    // key = unit.sort, value = index into sorted non-deleted takes list being browsed
    val currentTakeIndices: Map<Int, Int> = emptyMap(),
    val elapsedText: String = "00:00:00",
    val durationText: String = "00:00:00",
    // Pre-computed durations; key = take file absolutePath, value = "HH:MM:SS"
    val takeDurations: Map<String, String> = emptyMap()
)

class UnitListViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel(), KoinComponent {

    private val workbookRepository: IWorkbookRepository by inject()
    private val collectionRepository: ICollectionRepository by inject()
    private val appPreferences: IAppPreferences by inject()

    private val _uiState = MutableStateFlow(UnitListUiState())
    val uiState: StateFlow<UnitListUiState> = _uiState.asStateFlow()

    private val audioConnectionFactory: AudioPlayerConnectionFactory by inject()
    private var audioPlayer: IAudioPlayer? = null

    private val playerId = kotlin.random.Random.nextInt()
    private var playbackJob: Job? = null

    init {
        audioPlayer = AudioPlayerConnection(
            id = playerId,
            factory = audioConnectionFactory,
            scope = viewModelScope,
            controlDispatcher = Dispatchers.Default
        )

        viewModelScope.launch {
            audioPlayer?.events?.collect { event ->
                when (event) {
                    is AudioPlayerEvent.Play -> {
                        _uiState.update { it.copy(isPlaying = true) }
                        startProgressTicker()
                    }
                    is AudioPlayerEvent.Pause,
                    is AudioPlayerEvent.Stop,
                    is AudioPlayerEvent.Error -> {
                        _uiState.update { it.copy(isPlaying = false) }
                        stopProgressTicker()
                        if (event is AudioPlayerEvent.Error) {
                            _uiState.update { it.copy(error = event.message) }
                        }
                    }
                    is AudioPlayerEvent.Complete -> {
                        _uiState.update { it.copy(isPlaying = false, playbackProgress = 0f, elapsedText = "00:00:00") }
                        stopProgressTicker()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startProgressTicker() {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (isActive) {
                val durationMs = audioPlayer?.getDurationMs() ?: 0
                val positionMs = audioPlayer?.getLocationMs() ?: 0
                val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                _uiState.update {
                    it.copy(
                        playbackProgress = progress,
                        elapsedText = formatTime(positionMs),
                        durationText = formatTime(durationMs)
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopProgressTicker() {
        playbackJob?.cancel()
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    fun loadUnits() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val nav = appPreferences.navState.first()
                if (!nav.hasActiveChapter) {
                    _uiState.update { it.copy(isLoading = false, error = "No active chapter") }
                    return@launch
                }

                val sourceC = collectionRepository.getProjectSuspend(nav.workbookSourceId)
                val targetC = collectionRepository.getProjectSuspend(nav.workbookTargetId)

                if (sourceC == null || targetC == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Project not found") }
                    return@launch
                }

                val workbook = workbookRepository.get(sourceC, targetC)
                val chapter = workbook.target.chaptersFlow.firstOrNull { it.sort == nav.chapterSort }

                if (chapter == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Chapter not found", workbook = workbook) }
                    return@launch
                }

                _uiState.update { it.copy(workbook = workbook, chapter = chapter) }

                chapter.observableFlowChunks.collect { chunks ->
                    val units = chunks.map { chunk ->
                        UnitUiModel(
                            unit = chunk,
                            hasContent = chunk.hasSelectedAudio(),
                            takes = chunk.audio.getAllTakes().count { !it.isDeleted() }
                        )
                    }

                    // Build initial browse indices pointing at each unit's selected take.
                    // Preserve any indices the user has already set.
                    val initialIndices = chunks.associate { chunk ->
                        val takes = chunk.audio.getAllTakes()
                            .filter { !it.isDeleted() }
                            .sortedBy { it.number }
                        val selected = chunk.audio.getSelectedTake()
                        val idx = if (selected != null) takes.indexOf(selected).coerceAtLeast(0) else 0
                        chunk.sort to idx
                    }
                    val existing = _uiState.value.currentTakeIndices
                    val mergedIndices = initialIndices + existing.filter { (sort, _) ->
                        chunks.any { it.sort == sort }
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            units = units,
                            currentTakeIndices = mergedIndices
                        )
                    }

                    // Compute durations for any takes not yet in the cache.
                    launch(ioDispatcher) {
                        val existing = _uiState.value.takeDurations
                        val newDurations = mutableMapOf<String, String>()
                        chunks.forEach { chunk ->
                            chunk.audio.getAllTakes().filter { !it.isDeleted() }.forEach { take ->
                                val key = take.file.absolutePath
                                if (!existing.containsKey(key)) {
                                    try {
                                        val af = OratureAudioFile(take.file)
                                        val ms = (af.totalFrames.toLong() * 1000L / af.sampleRate).toInt()
                                        newDurations[key] = formatTime(ms)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        if (newDurations.isNotEmpty()) {
                            _uiState.update { it.copy(takeDurations = it.takeDurations + newDurations) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer?.release()
        stopProgressTicker()
    }

    // Returns the take the user is currently browsing for a unit (may differ from selected take).
    private fun getCurrentViewedTake(unit: Chunk): Take? {
        val takes = unit.audio.getAllTakes()
            .filter { !it.isDeleted() }
            .sortedBy { it.number }
        val index = _uiState.value.currentTakeIndices[unit.sort] ?: 0
        return takes.getOrNull(index)
    }

    fun togglePlay(unit: Chunk) {
        val take = getCurrentViewedTake(unit) ?: return
        playTake(take)
    }

    fun playTake(take: Take) {
        viewModelScope.launch {
            try {
                if (_uiState.value.currentPlayingTake == take && _uiState.value.isPlaying) {
                    audioPlayer?.pause()
                } else {
                    val reader = OratureAudioFile(take.file).reader()
                    audioPlayer?.load(reader)
                    audioPlayer?.play()
                    _uiState.update { it.copy(currentPlayingTake = take) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to play audio: ${e.message}") }
            }
        }
    }

    // Cycles through takes without changing the officially selected take.
    fun cycleTake(unit: Chunk, direction: Int) {
        val takes = unit.audio.getAllTakes()
            .filter { !it.isDeleted() }
            .sortedBy { it.number }
        if (takes.isEmpty()) return

        val currentIndex = _uiState.value.currentTakeIndices[unit.sort] ?: 0
        val newIndex = (currentIndex + direction + takes.size) % takes.size
        _uiState.update { state ->
            state.copy(currentTakeIndices = state.currentTakeIndices + (unit.sort to newIndex))
        }
    }

    // Explicitly marks the currently browsed take as the selected take.
    fun selectCurrentTake(unit: Chunk) {
        val take = getCurrentViewedTake(unit) ?: return
        unit.audio.selectTake(take)
    }

    fun deleteTake(unit: Chunk, take: Take) {
        take.deletedTimestamp.accept(DateHolder.now())
        val remaining = unit.audio.getAllTakes()
            .filter { !it.isDeleted() }
            .sortedBy { it.number }
        val clamped = (_uiState.value.currentTakeIndices[unit.sort] ?: 0)
            .coerceIn(0, (remaining.size - 1).coerceAtLeast(0))
        // Updating `units` guarantees the state object always changes (different takes count),
        // so StateFlow emits and recomposition fires even when the clamped index is unchanged.
        val updatedUnits = _uiState.value.units.map { uiModel ->
            if (uiModel.unit.sort == unit.sort) {
                uiModel.copy(hasContent = unit.hasSelectedAudio(), takes = remaining.size)
            } else uiModel
        }
        _uiState.update { state ->
            state.copy(
                units = updatedUnits,
                currentTakeIndices = state.currentTakeIndices + (unit.sort to clamped)
            )
        }
    }
}
