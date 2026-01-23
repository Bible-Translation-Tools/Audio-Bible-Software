package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ProjectManagementViewModel(
) : ViewModel(), KoinComponent {

    private val workbookDescriptorRepository: IWorkbookDescriptorRepository by inject()

    private val _uiState = MutableStateFlow<ProjectManagementUiState>(ProjectManagementUiState.Loading)
    val uiState: StateFlow<ProjectManagementUiState> = _uiState.asStateFlow()

    init {
        loadWorkbooks()
    }

    fun loadWorkbooks() {
        viewModelScope.launch {
            _uiState.value = ProjectManagementUiState.Loading
            try {
                val workbooks = workbookDescriptorRepository.getAll().blockingGet()
                _uiState.value = ProjectManagementUiState.Success(workbooks)
            } catch (e: Exception) {
                _uiState.value = ProjectManagementUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun onProjectClick(workbook: WorkbookDescriptor) {
        // TODO: Handle project click (navigation to Recording/Chapter list)
    }

    fun onNewProjectClick() {
        // TODO: Handle new project click (navigation to Project Wizard)
    }
}

sealed interface ProjectManagementUiState {
    data object Loading : ProjectManagementUiState
    data class Success(val projects: List<WorkbookDescriptor>) : ProjectManagementUiState
    data class Error(val message: String) : ProjectManagementUiState
}
