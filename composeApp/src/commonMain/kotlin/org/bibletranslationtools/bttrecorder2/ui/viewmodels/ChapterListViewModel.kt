package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class ChapterUiModel(
    val chapter: Chapter,
    val hasContent: Boolean = false,
    val progress: Float = 0f
)

data class ChapterListUiState(
    val isLoading: Boolean = false,
    val chapters: List<ChapterUiModel> = emptyList(),
    val workbook: Workbook? = null,
    val error: String? = null
)

class ChapterListViewModel : ViewModel(), KoinComponent {

    private val workbookRepository: IWorkbookRepository by inject()
    private val collectionRepository: ICollectionRepository by inject()

    private val _uiState = MutableStateFlow(ChapterListUiState())
    val uiState: StateFlow<ChapterListUiState> = _uiState.asStateFlow()

    private var loadingJob: Job? = null

    fun loadChapters(workbookSourceId: Int, workbookTargetId: Int) {
        loadingJob?.cancel()
        loadingJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, chapters = emptyList()) }
            try {
                val sourceC = collectionRepository.getProject(workbookSourceId).blockingGet()
                val targetC = collectionRepository.getProject(workbookTargetId).blockingGet()
                val workbook = workbookRepository.get(sourceC, targetC)

                if (workbook == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Workbook not found") }
                    return@launch
                }

                _uiState.update { it.copy(workbook = workbook) }

                // coroutineScope gives structured concurrency: all per-chapter collectors
                // are cancelled together when loadingJob is cancelled.
                coroutineScope {
                    workbook.target.chaptersFlow.collect { chapter ->
                        // Each chapter gets its own collector on observableFlowChunks.
                        // This fixes the race condition where chunksSuspend() returns the
                        // BehaviorRelay's initial empty list before the DB query completes.
                        launch {
                            chapter.observableFlowChunks.collect { chunks ->
                                val hasContent = chunks.any { it.hasSelectedAudio() }
                                val total = chunks.size
                                val started = chunks.count { it.hasSelectedAudio() }
                                val progress = if (total > 0) started.toFloat() / total else 0f
                                val uiModel = ChapterUiModel(chapter, hasContent, progress)

                                _uiState.update { state ->
                                    val list = state.chapters.toMutableList()
                                    val idx = list.indexOfFirst { it.chapter.sort == chapter.sort }
                                    if (idx != -1) list[idx] = uiModel else list.add(uiModel)
                                    list.sortBy { it.chapter.sort }
                                    state.copy(isLoading = false, workbook = workbook, chapters = list)
                                }
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }
}
