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
import org.bibletranslationtools.otter.common.data.primitives.ContentType
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

        // We deliberately do NOT drive isPlaying from the events flow.
        //
        //   1. `play()` is fire-and-forget on a control dispatcher, so a Pause
        //      event from the global worker (e.g., from the recorder or chapter
        //      player taking over) can fire after we've called play() but
        //      before our connection becomes active — that would flip the UI
        //      back to "paused" with the position reset.
        //   2. `pause()` does NOT emit a Stop, but `load()` may; treating Stop
        //      as "the user paused" reset our position state to zero.
        //
        // Instead, isPlaying is owned by user intent (set in playTake) and the
        // ticker; events are only used to surface errors.
        viewModelScope.launch {
            audioPlayer?.events?.collect { event ->
                if (event is AudioPlayerEvent.Error) {
                    _uiState.update { it.copy(error = event.message) }
                }
            }
        }
    }

    /**
     * Polls position + duration at 100 ms intervals and pushes them into UI
     * state. End-of-playback (position reaches duration) resets the slider and
     * flips isPlaying back to false. The ticker is otherwise only stopped by
     * an explicit user pause via [playTake].
     */
    private fun startProgressTicker() {
        if (playbackJob?.isActive == true) return
        playbackJob = viewModelScope.launch {
            while (isActive) {
                delay(100)
                val durationMs = audioPlayer?.getDurationMs() ?: 0
                val positionMs = audioPlayer?.getLocationMs() ?: 0
                val progress = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f

                if (durationMs > 0 && positionMs >= durationMs) {
                    _uiState.update {
                        it.copy(
                            isPlaying = false,
                            playbackProgress = 0f,
                            elapsedText = "00:00:00",
                            durationText = formatTime(durationMs)
                        )
                    }
                    break
                }

                _uiState.update {
                    it.copy(
                        playbackProgress = progress,
                        elapsedText = formatTime(positionMs),
                        durationText = formatTime(durationMs)
                    )
                }
            }
            playbackJob = null
        }
    }

    private fun stopProgressTicker() {
        playbackJob?.cancel()
        playbackJob = null
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

                chapter.observableFlowChunks.collect { allChunks ->
                    // Drop the chapter-meta chunk so the unit list only shows individual
                    // verses. The chapter's compiled take lives on `chapter.audio` (see
                    // ChapterTranslationBuilder) and the chapter list owns its UI; this
                    // screen is exclusively for per-verse takes.
                    val chunks = allChunks.filter { it.contentType == ContentType.TEXT }

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
        viewModelScope.launch(ioDispatcher) {
            try {
                val state = _uiState.value
                val isSameTakeLoaded = state.currentPlayingTake?.file?.absolutePath ==
                    take.file.absolutePath

                if (isSameTakeLoaded) {
                    // The take is already in the player. Toggle without reloading
                    // — reloading would reset the worker's position to 0, which is
                    // why pressing pause used to "snap back" the slider.
                    if (state.isPlaying) {
                        audioPlayer?.pause()
                        _uiState.update { it.copy(isPlaying = false) }
                        stopProgressTicker()
                    } else {
                        audioPlayer?.play()
                        _uiState.update { it.copy(isPlaying = true) }
                        startProgressTicker()
                    }
                } else {
                    // Switching to a different take. If something else is playing,
                    // pause first so the previous ticker stops cleanly.
                    if (state.isPlaying) {
                        audioPlayer?.pause()
                        stopProgressTicker()
                    }

                    val reader = OratureAudioFile(take.file).reader()
                    audioPlayer?.load(reader)
                    val durationMs = audioPlayer?.getDurationMs() ?: 0
                    audioPlayer?.play()
                    _uiState.update {
                        it.copy(
                            currentPlayingTake = take,
                            isPlaying = true,
                            playbackProgress = 0f,
                            elapsedText = "00:00:00",
                            durationText = formatTime(durationMs)
                        )
                    }
                    startProgressTicker()
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
        // Stop playback if the deleted take is the one currently loaded.
        val playingSame = _uiState.value.currentPlayingTake?.file?.absolutePath ==
            take.file.absolutePath
        if (playingSame) {
            runCatching { audioPlayer?.pause() }
            stopProgressTicker()
        }

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
                currentTakeIndices = state.currentTakeIndices + (unit.sort to clamped),
                isPlaying = if (playingSame) false else state.isPlaying,
                currentPlayingTake = if (playingSame) null else state.currentPlayingTake,
                playbackProgress = if (playingSame) 0f else state.playbackProgress,
                elapsedText = if (playingSame) "00:00:00" else state.elapsedText,
                durationText = if (playingSame) "00:00:00" else state.durationText
            )
        }
    }
}
