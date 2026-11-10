package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.errUnknown
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.collections.UpgradeBookEdition
import org.bibletranslationtools.otter.common.domain.project.OpenWorkbook
import org.bibletranslationtools.otter.common.domain.project.exporter.AudioProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.ChapterCompletion
import org.bibletranslationtools.otter.common.domain.project.exporter.ChapterStatus
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportOptions
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportResult
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportRules
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportType
import org.bibletranslationtools.otter.common.domain.project.exporter.IProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.ProjectExporterCallback
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.BackupProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.SourceProjectExporter
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/** One chapter row in the export dialog (JVM: `ChapterDescriptor`). Progress is binary in the JVM
 *  (1.0 when the chapter is done, else 0.0). */
data class OratureExportChapter(
    val sort: Int,
    val progress: Double,
    val selectable: Boolean,
    val selected: Boolean,
    /** What the export rules decide [selectable] from. */
    val status: ChapterStatus
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
    val error: String? = null,
    /** Chapters that keep an earlier source edition's verses: a published export mixes structures (A13). */
    val heldBackChapters: List<Int> = emptyList()
) {
    /** Selected chapters that would mix verse structures in a published export; a backup restores them as they are. */
    val mixedEditionChapters: List<Int>
        get() = if (selectedType == ExportType.BACKUP) emptyList()
        else chapters.filter { it.selected && it.sort in heldBackChapters }.map { it.sort }
}

/**
 * Drives the project-export dialog (JVM: `ExportProjectViewModel`): loads the workbook's chapters
 * (with a selectable flag from progress), dispatches to the right shared exporter by [ExportType]
 * (Backup / Source Audio / Listen), reports progress, and estimates the output size. Backup exports
 * everything; Source/Listen export the selected chapters.
 */
class OratureExportProjectViewModel(
    private val workbookDescriptorId: Int
) : ViewModel(), KoinComponent {

    private val openWorkbook: OpenWorkbook by inject()
    private val audioExporter: AudioProjectExporter by inject()
    private val sourceExporter: SourceProjectExporter by inject()
    private val backupExporter: BackupProjectExporter by inject()
    private val upgradeBookEdition: UpgradeBookEdition by inject()
    private val chapterCompletion: ChapterCompletion by inject()

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
        // BurritoWrapperExporter exists but is NOT bound in Koin, because it needs the same
        // `AuthProvider` and `IAppInfo` ports that leave SourceProjectExporter unresolvable — see
        // the note in KoinModules and `SharedGraphWiringTest.source project exporter is
        // unresolvable while AuthProvider and IAppInfo are unbound`. Nothing in either app offers
        // this export type yet, so this branch is unreachable; it fails loudly rather than
        // silently exporting some other format if that changes before the ports land.
        ExportType.BURRITO_WRAPPER -> error(
            "Burrito wrapper export is not wired up: AuthProvider and IAppInfo are unbound."
        )
    }

    private fun load() {
        launchLogged {
            try {
                // open() dispatches to IO itself; openWithChapters() would also work out each
                // chapter's completion, which the status below does again.
                val opened = openWorkbook.open(workbookDescriptorId)
                val wb = opened.workbook
                workbook = wb
                // IO: the status reads each chapter's takes and files.
                val chapters = withContext(Dispatchers.IO) {
                    wb.target.chapters.toList().await().sortedBy { it.sort }.map { chapter ->
                        val status = chapterCompletion.status(wb, chapter, opened.mode)
                        OratureExportChapter(
                            sort = chapter.sort,
                            progress = if (status.audioRecorded) 1.0 else 0.0,
                            selectable = false,
                            selected = false,
                            status = status
                        )
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    bookTitle = wb.target.title.ifEmpty { wb.target.slug.uppercase() },
                    // Derive selectability for the default type and pre-select all selectable.
                    chapters = applySelectability(chapters, _uiState.value.selectedType),
                    heldBackChapters = runCatching {
                        withContext(Dispatchers.IO) { upgradeBookEdition.editionState(wb.target.collectionId) }
                    }.onFailure { logFailure("loading the book's source editions", it) }
                        .getOrNull()?.heldBackChapters.orEmpty()
                )
                recomputeEstimate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading export options", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: getString(Res.string.errUnknown))
            }
        }
    }

    fun selectType(type: ExportType) {
        if (_uiState.value.selectedType == type) return
        // JVM onSelectExportType: re-derive which chapters are selectable for this type and
        // pre-select all of them.
        _uiState.value = _uiState.value.copy(
            selectedType = type,
            chapters = applySelectability(_uiState.value.chapters, type)
        )
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

    /** Select-all / deselect-all toggle for the table header checkbox (only affects selectable rows). */
    fun toggleAll(selected: Boolean) {
        _uiState.value = _uiState.value.copy(
            chapters = _uiState.value.chapters.map {
                if (it.selectable) it.copy(selected = selected) else it
            }
        )
        recomputeEstimate()
    }

    /** Which chapters [type] takes, per its [ExportRules]. Selectable rows start pre-selected. */
    private fun applySelectability(
        chapters: List<OratureExportChapter>,
        type: ExportType
    ): List<OratureExportChapter> = chapters.map { ch ->
        val selectable = ExportRules.forType(type).canExport(ch.status)
        ch.copy(selectable = selectable, selected = selectable)
    }

    private fun selectedChapters(): List<Int> = _uiState.value.chapters.filter { it.selected }.map { it.sort }

    private fun recomputeEstimate() {
        val wb = workbook ?: return
        val type = _uiState.value.selectedType
        val chapters = selectedChapters()
        launchLogged {
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
        launchLogged {
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
