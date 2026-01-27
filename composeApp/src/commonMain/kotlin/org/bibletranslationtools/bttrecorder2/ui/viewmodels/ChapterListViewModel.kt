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
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class ChapterListUiState(
    val isLoading: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
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
                val targetBook = workbook.target
                val chapters = targetBook.chapters.collectInto<List<Chapter>>(mutableListOf<Chapter>(), { list, chapter -> (list as MutableList).add(chapter) }).blockingGet()

                _uiState.update { 
                    it.copy(
                        isLoading = false, 
                        workbook = workbook, 
                        chapters = chapters
                    ) 
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }
}
