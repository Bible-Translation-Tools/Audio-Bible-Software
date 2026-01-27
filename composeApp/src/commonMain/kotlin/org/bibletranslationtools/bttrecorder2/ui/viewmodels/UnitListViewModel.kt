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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class UnitListUiState(
    val isLoading: Boolean = false,
    val units: List<Chunk> = emptyList(),
    val chapter: Chapter? = null,
    val workbook: Workbook? = null,
    val error: String? = null
)

class UnitListViewModel : ViewModel(), KoinComponent {

    private val workbookRepository: IWorkbookRepository by inject()
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository by inject()
    private val collectionRepository: ICollectionRepository by inject()

    private val _uiState = MutableStateFlow(UnitListUiState())
    val uiState: StateFlow<UnitListUiState> = _uiState.asStateFlow()

    fun loadUnits(
        workbookSourceId: Int,
        workbookTargetId: Int,
        chapterNumber: Int
    ) {
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
                val chapter: Chapter = targetBook.chapters.filter { it.sort == chapterNumber }.blockingFirst()

                if (chapter == null) {
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            workbook = workbook,
                            error = "Chapter $chapterNumber not found" 
                        ) 
                    }
                    return@launch
                }

                val units = chapter.chunks.blockingGet()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        workbook = workbook,
                        chapter = chapter,
                        units = units
                    )
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }
}
