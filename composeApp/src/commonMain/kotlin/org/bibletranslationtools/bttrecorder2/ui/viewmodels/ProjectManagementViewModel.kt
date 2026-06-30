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
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.rx2.await
import org.jetbrains.compose.resources.getString
import btt_recorder2.composeapp.generated.resources.Res
import btt_recorder2.composeapp.generated.resources.err_unknown
import btt_recorder2.composeapp.generated.resources.err_delete_project
import btt_recorder2.composeapp.generated.resources.import_failed
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

class ProjectManagementViewModel(
) : ViewModel(), KoinComponent {

    private val workbookRepository: IWorkbookRepository by inject()
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository by inject()
    private val importProjectUseCase: ImportProjectUseCase by inject()
    private val directoryProvider: IDirectoryProvider by inject()

    private val _uiState = MutableStateFlow<ProjectManagementUiState>(ProjectManagementUiState.Loading)
    val uiState: StateFlow<ProjectManagementUiState> = _uiState.asStateFlow()

    private val _importState = MutableStateFlow<ProjectImportState>(ProjectImportState.Idle)
    val importState: StateFlow<ProjectImportState> = _importState.asStateFlow()

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

    /**
     * Imports a project archive (.orature / .zip resource container / .tstudio)
     * chosen by the user via [importProjectUseCase], which auto-detects the
     * format. FileKit hands us bytes, so we stage to a temp file the use case can
     * open, then refresh the project list on success.
     */
    fun importProject(platformFile: PlatformFile) {
        if (_importState.value is ProjectImportState.InProgress) return
        _importState.value = ProjectImportState.InProgress
        viewModelScope.launch {
            var staged: File? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val ext = platformFile.name.substringAfterLast('.', "").lowercase().ifEmpty { "zip" }
                    val tmp = File.createTempFile("import_", ".$ext", directoryProvider.tempDirectory)
                    tmp.writeBytes(platformFile.readBytes())
                    staged = tmp
                    importProjectUseCase.import(tmp).await()
                }
                when (result) {
                    ImportResult.SUCCESS, ImportResult.ALREADY_EXISTS -> {
                        loadWorkbooks()
                        _importState.value = ProjectImportState.Success
                    }
                    else -> _importState.value = ProjectImportState.Error(
                        getString(Res.string.import_failed)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importState.value = ProjectImportState.Error(
                    e.message ?: getString(Res.string.import_failed)
                )
            } finally {
                staged?.let { runCatching { it.delete() } }
            }
        }
    }

    fun acknowledgeImport() {
        _importState.value = ProjectImportState.Idle
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

sealed interface ProjectImportState {
    data object Idle : ProjectImportState
    data object InProgress : ProjectImportState
    data object Success : ProjectImportState
    data class Error(val message: String) : ProjectImportState
}
