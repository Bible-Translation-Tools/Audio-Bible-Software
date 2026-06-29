package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bibletranslationtools.bttrecorder2.ui.navigation.ProjectWizardRoute
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.jetbrains.compose.resources.getString
import btt_recorder2.composeapp.generated.resources.Res
import btt_recorder2.composeapp.generated.resources.err_unknown
import btt_recorder2.composeapp.generated.resources.err_delete_project
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ProjectManagementViewModel(
) : ViewModel(), KoinComponent {

    private val workbookRepository: IWorkbookRepository by inject()
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
                _uiState.value = ProjectManagementUiState.Error(e.message ?: getString(Res.string.err_unknown))
            }
        }
    }

    fun onProjectClick() {

    }

    fun onNewProjectClick() {
        // TODO: Handle new project click (navigation to Project Wizard)
    }

    fun deleteWorkbook(workbook: WorkbookDescriptor) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    workbookDescriptorRepository.deleteSuspend(listOf(workbook))
                }
                loadWorkbooks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = ProjectManagementUiState.Error(e.message ?: getString(Res.string.err_delete_project))
            }
        }
    }
}

sealed interface ProjectManagementUiState {
    data object Loading : ProjectManagementUiState
    data class Success(val projects: List<WorkbookDescriptor>) : ProjectManagementUiState
    data class Error(val message: String) : ProjectManagementUiState
}
