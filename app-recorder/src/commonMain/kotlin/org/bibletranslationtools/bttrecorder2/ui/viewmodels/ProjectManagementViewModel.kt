package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DescribeSourceEditions
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.err_delete_project
import org.bibletranslationtools.shared.resources.err_unknown
import org.bibletranslationtools.shared.resources.import_failed
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

enum class SortField { LANGUAGE, BOOK, PROGRESS }
enum class SortDirection { ASC, DESC }

enum class ProgressFilter {
    NOT_STARTED, IN_PROGRESS, COMPLETED;

    fun matches(progress: Double): Boolean = when (this) {
        NOT_STARTED -> progress <= 0.0
        IN_PROGRESS -> progress > 0.0 && progress < 1.0
        COMPLETED -> progress >= 1.0
    }
}

data class SortState(
    val field: SortField = SortField.BOOK,
    val direction: SortDirection = SortDirection.ASC
)

data class ProjectGroup(
    val id: String,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val books: List<WorkbookDescriptor>
)

/** Books by [progress] (absent reads 0); equal progress keeps Bible order whichever way it runs. */
internal fun List<WorkbookDescriptor>.sortedByProgress(
    progress: Map<Int, Double>,
    direction: SortDirection
): List<WorkbookDescriptor> {
    val byProgress = compareBy<WorkbookDescriptor> { progress[it.id] ?: 0.0 }
    return sortedWith((if (direction == SortDirection.DESC) byProgress.reversed() else byProgress).thenBy { it.sort })
}

fun ProjectGroup.booksByType(): Map<String, List<WorkbookDescriptor>> =
    books.groupBy { it.targetCollection.resourceContainer?.identifier.orEmpty() }

class ProjectManagementViewModel : ViewModel(), KoinComponent {

    private val collectionRepository: ICollectionRepository by inject()
    private val contentRepository: IContentRepository by inject()
    private val takeRepository: ITakeRepository by inject()
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository by inject()
    private val importProjectUseCase: ImportProjectUseCase by inject()
    private val directoryProvider: ITempFileProvider by inject()
    private val describeSourceEditions: DescribeSourceEditions by inject()
    private val appPreferences: IAppPreferences by inject()

    private val _rawWorkbooks = MutableStateFlow<List<WorkbookDescriptor>?>(null)
    private val _sortState = MutableStateFlow(SortState())
    private val _updatesAvailable = MutableStateFlow<Set<Int>>(emptySet())
    private val _progress = MutableStateFlow<Map<Int, Double>>(emptyMap())
    private var progressIds: Set<Int> = emptySet()
    private var progressJob: Job? = null

    private val _uiState = MutableStateFlow<ProjectManagementUiState>(ProjectManagementUiState.Loading)
    val uiState: StateFlow<ProjectManagementUiState> = _uiState.asStateFlow()

    private val _importState = MutableStateFlow<ProjectImportState>(ProjectImportState.Idle)
    val importState: StateFlow<ProjectImportState> = _importState.asStateFlow()

    fun loadWorkbooks() {
        launchLogged {
            _uiState.value = ProjectManagementUiState.Loading
            try {
                val workbooks = workbookDescriptorRepository.getAllSuspend()
                _rawWorkbooks.value = workbooks
                _updatesAvailable.value = updatesAvailable(workbooks)
                publish()
                refreshProgress()
            } catch (e: Exception) {
                logFailure("loading the project list", e)
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
        publish()
    }

    /** Computes progress for [ids] only: it reads every chapter of a book, too slow for whole languages. */
    fun loadProgress(ids: Set<Int>) {
        progressIds = ids
        refreshProgress()
    }

    /** Stops calculating progress: the book list left the screen, and a book opened from it should not wait on it. */
    fun stopProgress() {
        progressIds = emptySet()
        progressJob?.cancel()
        progressJob = null
    }

    private fun refreshProgress() {
        val books = _rawWorkbooks.value.orEmpty().filter { it.id in progressIds }
        if (books.isEmpty()) return
        progressJob?.cancel()
        progressJob = launchLogged { publishProgressOf(books) }
    }

    private fun publish() {
        val workbooks = _rawWorkbooks.value ?: return
        _uiState.value = ProjectManagementUiState.Success(
            groups = groupAndSort(workbooks, _sortState.value),
            sortState = _sortState.value,
            updatesAvailable = _updatesAvailable.value,
            progress = _progress.value
        )
    }

    /** Each project's completion, 0.0 to 1.0, shown as each one is ready; a project that fails reads 0. */
    private suspend fun publishProgressOf(workbooks: List<WorkbookDescriptor>) = coroutineScope {
        workbooks.forEach { workbook ->
            launch {
                val progress = try {
                    bookProgress(workbook)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure("computing project progress", e)
                    0.0
                }
                _progress.update { it + (workbook.id to progress) }
                publish()
            }
        }
    }

    /**
     * The share of the book's verses recorded. A chapter with a take of its own, recorded or compiled,
     * counts all its verses, as the chapter list counts it done (ChapterUiModel.completion).
     *
     * Read with plain queries, one per chapter, not through a Workbook: building one loads the whole
     * book, source and target, through subscriptions the repository owns, so cancelling could not stop it.
     */
    private suspend fun bookProgress(descriptor: WorkbookDescriptor): Double {
        var recorded = 0
        var total = 0
        collectionRepository.getChildrenSuspend(descriptor.targetCollection).forEach { chapter ->
            val contents = contentRepository.getByCollectionSuspend(chapter)
            val verses = contents.filter { it.type == ContentType.TEXT }
            val chapterContent = contents.firstOrNull { it.type == ContentType.META }
            val hasChapterTake = chapterContent != null && (
                chapterContent.selectedTake?.deleted == null && chapterContent.selectedTake != null ||
                    takeRepository.getByContent(chapterContent).await().isNotEmpty()
                )
            total += verses.size
            recorded += if (hasChapterTake) verses.size else verses.count { it.selectedTake?.deleted == null && it.selectedTake != null }
        }
        return if (total == 0) 0.0 else recorded.toDouble() / total
    }

    /** Ids of the projects whose source edition has a newer edition installed. */
    private suspend fun updatesAvailable(workbooks: List<WorkbookDescriptor>): Set<Int> {
        val editions = workbooks.mapNotNull { it.sourceCollection.resourceContainer }
        val summaries = describeSourceEditions.describeAll(editions)
        return workbooks
            .filter { workbook -> workbook.sourceCollection.resourceContainer?.let { summaries[it.id]?.updateAvailable } == true }
            .map { it.id }
            .toSet()
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
            SortField.PROGRESS -> books.sortedByProgress(_progress.value, sort.direction)
        }
    }

    /**
     * Makes [workbook] the active project, then calls [onOpened]. The write must land first: the
     * chapter list reads it as it opens, and loses the race otherwise ("No active project", seen
     * on a first-ever run, where the write has to create the preferences file).
     */
    fun openProject(workbook: WorkbookDescriptor, onOpened: () -> Unit) {
        launchLogged {
            appPreferences.setActiveWorkbook(
                sourceId = workbook.sourceCollection.id,
                targetId = workbook.targetCollection.id
            )
            onOpened()
        }
    }

    fun onNewProjectClick() {}

    fun importProject(platformFile: PlatformFile) {
        if (_importState.value is ProjectImportState.InProgress) return
        _importState.value = ProjectImportState.InProgress
        launchLogged {
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
                logFailure("importing the project", e)
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
        launchLogged {
            try {
                withContext(Dispatchers.IO) {
                    workbookDescriptorRepository.deleteSuspend(listOf(workbook))
                }
                loadWorkbooks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("deleting the project", e)
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
        val sortState: SortState = SortState(),
        /** Ids of projects with a newer source edition installed. */
        val updatesAvailable: Set<Int> = emptySet(),
        val progress: Map<Int, Double> = emptyMap()
    ) : ProjectManagementUiState
    data class Error(val message: String) : ProjectManagementUiState
}

sealed interface ProjectImportState {
    data object Idle : ProjectImportState
    data object InProgress : ProjectImportState
    data object Success : ProjectImportState
    data class Error(val message: String) : ProjectImportState
}
