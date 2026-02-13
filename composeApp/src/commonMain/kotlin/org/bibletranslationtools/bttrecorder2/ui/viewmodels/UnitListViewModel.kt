package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.data.workbook.Take
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import org.bibletranslationtools.otter.common.data.workbook.DateHolder
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerEvent
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.CoroutineDispatcher

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
    val currentPlayingTake: Take? = null
)

class UnitListViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel(), KoinComponent {

    private val workbookRepository: IWorkbookRepository by inject()
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository by inject()
    private val collectionRepository: ICollectionRepository by inject()

    private val _uiState = MutableStateFlow(UnitListUiState())
    val uiState: StateFlow<UnitListUiState> = _uiState.asStateFlow()

    private val audioConnectionFactory: AudioPlayerConnectionFactory by inject()
    private var audioPlayer: IAudioPlayer? = null

    // Unique ID for this player connection
    private val playerId = kotlin.random.Random.nextInt()

    private var playbackJob: Job? = null

    init {
        // Initialize player
        audioPlayer = AudioPlayerConnection(
            id = playerId,
            factory = audioConnectionFactory,
            scope = viewModelScope
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
                        _uiState.update { it.copy(isPlaying = false, playbackProgress = 0f) }
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
                val duration = audioPlayer?.getDurationMs() ?: 0
                val position = audioPlayer?.getLocationMs() ?: 0
                val progress = if (duration > 0) position.toFloat() / duration else 0f
                _uiState.update { it.copy(playbackProgress = progress) }
                delay(100)
            }
        }
    }

    private fun stopProgressTicker() {
        playbackJob?.cancel()
    }

    fun loadUnits(workbookSourceId: Int, workbookTargetId: Int, chapterNumber: Int) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val sourceC = collectionRepository.getProjectSuspend(workbookSourceId)
                val targetC = collectionRepository.getProjectSuspend(workbookTargetId)

                if (sourceC == null || targetC == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Project not found") }
                    return@launch
                }

                val workbook = workbookRepository.get(sourceC, targetC)

                val chapter = workbook.target.chaptersFlow.firstOrNull { it.sort == chapterNumber }

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
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            units = units
                        )
                    }
                }

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

    // Audio Controls
    fun togglePlay(unit: Chunk) {
        val take = unit.audio.getSelectedTake()
        if (take != null) {
            playTake(take)
        }
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

    fun pauseAudio() {
        audioPlayer?.pause()
    }

    // Take Management
    fun deleteTake(unit: Chunk, take: Take) {
        take.deletedTimestamp.accept(DateHolder.now())
    }

    fun selectTake(unit: Chunk, take: Take) {
        unit.audio.selectTake(take)
    }

    fun cycleTake(unit: Chunk, direction: Int) {
        val takes = unit.audio.getAllTakes().filter { !it.isDeleted() }.sortedBy { it.number }
        if (takes.isEmpty()) return

        val currentTake = unit.audio.getSelectedTake()
        val currentIndex = if (currentTake != null) takes.indexOf(currentTake) else -1

        var newIndex = currentIndex + direction
        if (newIndex >= takes.size) newIndex = 0
        if (newIndex < 0) newIndex = takes.size - 1

        val newTake = takes[newIndex]
        selectTake(unit, newTake)
    }
}