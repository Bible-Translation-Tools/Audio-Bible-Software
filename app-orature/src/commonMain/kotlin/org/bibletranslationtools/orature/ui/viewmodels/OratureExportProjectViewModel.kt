package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.reactivex.Single
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.project.exporter.AudioProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportOptions
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportResult
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportType
import org.bibletranslationtools.otter.common.domain.project.exporter.IProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.ProjectExporterCallback
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.BackupProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.SourceProjectExporter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/** One chapter row in the export dialog (JVM: `ChapterDescriptor`). */
data class OratureExportChapter(
    val sort: Int,
    val selectable: Boolean,
    val selected: Boolean
)

/** UI state for the export dialog (JVM: `ExportProjectDialog` + `ExportProjectViewModel`). */
data class OratureExportUiState(
    val isLoading: Boolean = true,
    val bookTitle: String = "",
    val chapters: List<OratureExportChapter> = emptyList(),
    val selectedType: ExportType = ExportType.BACKUP,
    val estimatedSizeBytes: Long = 0L,
    /** Non-null while exporting (0..1); null otherwise. */
    val progress: Float? = null,
    val done: Boolean = false,
    val error: String? = null
)

/**
 * Drives the project-export dialog (JVM: `ExportProjectViewModel`): loads the workbook's chapters
 * (with a selectable flag from progress), dispatches to the right shared exporter by [ExportType]
 * (Backup / Source Audio / Listen), reports progress, and estimates the output size. Backup exports
 * everything; Source/Listen export the selected chapters.
 */
class OratureExportProjectViewModel(
    private val workbookDescriptorId: Int
) : ViewModel(), KoinComponent {

    private val workbookDescriptorRepo: IWorkbookDescriptorRepository by inject()
    private val workbookRepository: IWorkbookRepository by inject()
    private val audioExporter: AudioProjectExporter by inject()
    private val sourceExporter: SourceProjectExporter by inject()
    private val backupExporter: BackupProjectExporter by inject()

    private val _uiState = MutableStateFlow(OratureExportUiState())
    val uiState: StateFlow<OratureExportUiState> = _uiState.asStateFlow()

    private var workbook: Workbook? = null
    // The exported artifact (from the exporter's success callback), for "Show Location".
    private var exportedFile: File? = null
    fun exportedLocation(): File? = exportedFile

    init {
        load()
    }

    private fun exporterFor(type: ExportType): IProjectExporter = when (type) {
        ExportType.LISTEN -> audioExporter
        ExportType.SOURCE_AUDIO, ExportType.PUBLISH -> sourceExporter
        ExportType.BACKUP -> backupExporter
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val descriptor = workbookDescriptorRepo.getByIdSuspend(workbookDescriptorId)
                        ?: error("No workbook descriptor with id=$workbookDescriptorId")
                    val wb = workbookRepository.get(descriptor.sourceCollection, descriptor.targetCollection)
                    workbook = wb
                    val chapters = wb.target.chapters.toList().blockingGet()
                        .sortedBy { it.sort }
                        .map { chapter ->
                            val hasAudio = chapter.hasSelectedAudio()
                            OratureExportChapter(chapter.sort, selectable = hasAudio, selected = hasAudio)
                        }
                    val title = wb.target.title.ifEmpty { wb.target.slug.uppercase() }
                    title to chapters
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    bookTitle = loaded.first,
                    chapters = loaded.second
                )
                recomputeEstimate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    fun selectType(type: ExportType) {
        if (_uiState.value.selectedType == type) return
        _uiState.value = _uiState.value.copy(selectedType = type)
        recomputeEstimate()
    }

    fun toggleChapter(sort: Int) {
        _uiState.value = _uiState.value.copy(
            chapters = _uiState.value.chapters.map {
                if (it.sort == sort && it.selectable) it.copy(selected = !it.selected) else it
            }
        )
        recomputeEstimate()
    }

    private fun selectedChapters(): List<Int> = _uiState.value.chapters.filter { it.selected }.map { it.sort }

    private fun recomputeEstimate() {
        val wb = workbook ?: return
        val type = _uiState.value.selectedType
        val chapters = selectedChapters()
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                runCatching { exporterFor(type).estimateExportSize(wb, chapters) }.getOrDefault(0L)
            }
            _uiState.value = _uiState.value.copy(estimatedSizeBytes = size)
        }
    }

    /** Run the export into [directory] (JVM: exportWorkbook). Backup ignores chapter selection. */
    fun export(directory: File) {
        val wb = workbook ?: return
        val type = _uiState.value.selectedType
        val chapters = selectedChapters().takeIf { type != ExportType.BACKUP && it.isNotEmpty() }
        exportedFile = directory
        _uiState.value = _uiState.value.copy(progress = 0f, error = null)
        viewModelScope.launch {
            val callback = object : ProjectExporterCallback {
                override fun onNotifySuccess(project: Collection, file: File) { exportedFile = file }
                override fun onError(project: Collection) {}
                override fun onNotifyProgress(percent: Double, messageKey: String?) {
                    _uiState.value = _uiState.value.copy(progress = (percent / 100.0).toFloat().coerceIn(0f, 1f))
                }
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    exporterFor(type)
                        .export(directory, wb, callback, chapters?.let { ExportOptions(it) })
                        .await()
                }.getOrElse { ExportResult.FAILURE }
            }
            _uiState.value = if (result == ExportResult.SUCCESS) {
                _uiState.value.copy(progress = null, done = true)
            } else {
                _uiState.value.copy(progress = null, error = "export_failed")
            }
        }
    }

    fun acknowledgeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
