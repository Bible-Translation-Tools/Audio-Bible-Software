package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
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
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository by inject()
    private val collectionRepository: ICollectionRepository by inject()

    private val _uiState = MutableStateFlow(ChapterListUiState())
    val uiState: StateFlow<ChapterListUiState> = _uiState.asStateFlow()

    fun loadChapters(workbookSourceId: Int, workbookTargetId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // 1. Fetch WorkbookDescriptor
                val sourceC = collectionRepository.getProject(workbookSourceId).blockingGet()
                val targetC = collectionRepository.getProject(workbookTargetId).blockingGet()

                val workbook = workbookRepository.get(sourceC, targetC)

                if (workbook == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Workbook not found") }
                    return@launch
                }
                
                // 2. Fetch Chapters (Children of target collection)
                // Use chaptersFlow to reactively get chapters
                workbook.target.chaptersFlow
                    .collect { chapter ->
                        // Fetch details for UI model
                        val chunks = chapter.chunksSuspend()
                        val hasContent = chunks.any { it.hasSelectedAudio() }
                        val totalChunks = chunks.size
                        val startedChunks = chunks.count { it.hasSelectedAudio() }
                        val progress = if (totalChunks > 0) startedChunks.toFloat() / totalChunks else 0f

                        val uiModel = ChapterUiModel(chapter, hasContent, progress)

                        _uiState.update { state ->
                            val currentChapters = state.chapters.toMutableList()
                            // Check if chapter already exists (by sort/id) to update or append
                            val index = currentChapters.indexOfFirst { it.chapter.sort == chapter.sort }
                            if (index != -1) {
                                currentChapters[index] = uiModel
                            } else {
                                currentChapters.add(uiModel)
                            }
                            // Sort chapters by 'sort'
                            currentChapters.sortBy { it.chapter.sort }
                            
                            state.copy(
                                isLoading = false,
                                workbook = workbook,
                                chapters = currentChapters
                            )
                        }
                    }

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }
}
