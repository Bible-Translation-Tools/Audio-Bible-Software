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
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.rx2.await
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.err_unknown
import org.bibletranslationtools.shared.resources.err_delete_project
import org.bibletranslationtools.shared.resources.import_failed
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

enum class SortField { LANGUAGE, BOOK, PROGRESS }
enum class SortDirection { ASC, DESC }

data class SortState(
    val field: SortField = SortField.LANGUAGE,
    val direction: SortDirection = SortDirection.ASC
)

data class ProjectGroup(
    val id: String,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val books: List<WorkbookDescriptor>
)

class ProjectManagementViewModel : ViewModel(), KoinComponent {

    private val workbookRepository: IWorkbookRepository by inject()
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository by inject()
    private val importProjectUseCase: ImportProjectUseCase by inject()
    private val directoryProvider: IDirectoryProvider by inject()

    private val _rawWorkbooks = MutableStateFlow<List<WorkbookDescriptor>?>(null)
    private val _sortState = MutableStateFlow(SortState())

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
                _rawWorkbooks.value = workbooks
                _uiState.value = ProjectManagementUiState.Success(
                    groups = groupAndSort(workbooks, _sortState.value),
                    sortState = _sortState.value
                )
            } catch (e: Exception) {
                _uiState.value = ProjectManagementUiState.Error(e.message ?: getString(Res.string.err_unknown))
            }
        }
    }

    fun toggleSort(field: SortField) {
        val current = _sortState.value
        _sortState.value = if (current.field == field) {
            current.copy(
                direction = if (current.direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
            )
        } else {
            SortState(field = field, direction = SortDirection.ASC)
        }
        val workbooks = _rawWorkbooks.value ?: return
        _uiState.value = ProjectManagementUiState.Success(
            groups = groupAndSort(workbooks, _sortState.value),
            sortState = _sortState.value
        )
    }

    private fun groupAndSort(
        workbooks: List<WorkbookDescriptor>,
        sort: SortState
    ): List<ProjectGroup> {
        val groups = workbooks
            .groupBy { "${it.sourceLanguage.slug}_${it.targetLanguage.slug}" }
            .map { (id, books) ->
                val first = books.first()
                ProjectGroup(
                    id = id,
                    sourceLanguage = first.sourceLanguage,
                    targetLanguage = first.targetLanguage,
                    books = sortBooks(books, sort)
                )
            }

        return when (sort.field) {
            SortField.LANGUAGE -> {
                val sorted = groups.sortedBy { it.targetLanguage.anglicizedName }
                if (sort.direction == SortDirection.DESC) sorted.reversed() else sorted
            }
            // BOOK and PROGRESS sort within groups — groups stay in language order
            SortField.BOOK, SortField.PROGRESS ->
                groups.sortedBy { it.targetLanguage.anglicizedName }
        }
    }

    private fun sortBooks(books: List<WorkbookDescriptor>, sort: SortState): List<WorkbookDescriptor> {
        return when (sort.field) {
            SortField.LANGUAGE -> books.sortedBy { it.sort }
            SortField.BOOK -> {
                val sorted = books.sortedBy { it.sort }
                if (sort.direction == SortDirection.DESC) sorted.reversed() else sorted
            }
            SortField.PROGRESS -> books.sortedBy { it.sort }
        }
    }

    fun onProjectClick() {}

    fun onNewProjectClick() {}

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
                _uiState.value = ProjectManagementUiState.Error(
                    e.message ?: getString(Res.string.err_delete_project)
                )
            }
        }
    }
}

sealed interface ProjectManagementUiState {
    data object Loading : ProjectManagementUiState
    data class Success(
        val groups: List<ProjectGroup>,
        val sortState: SortState = SortState()
    ) : ProjectManagementUiState
    data class Error(val message: String) : ProjectManagementUiState
}

sealed interface ProjectImportState {
    data object Idle : ProjectImportState
    data object InProgress : ProjectImportState
    data object Success : ProjectImportState
    data class Error(val message: String) : ProjectImportState
}
