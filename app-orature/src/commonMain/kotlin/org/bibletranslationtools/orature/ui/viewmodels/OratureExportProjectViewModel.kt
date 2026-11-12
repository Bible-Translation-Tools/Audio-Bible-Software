package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.shared.ui.platform.canOpenInFileManager
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.errUnknown
import org.bibletranslationtools.otter.common.api.persistence.IFileIOFactory
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.collections.UpgradeBookEdition
import org.bibletranslationtools.otter.common.domain.project.OpenWorkbook
import org.bibletranslationtools.otter.common.domain.project.exporter.AudioProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportOptions
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportResult
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportType
import org.bibletranslationtools.otter.common.domain.project.exporter.IProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.ProjectExporterCallback
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.BackupProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.SourceProjectExporter
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One chapter row in the export dialog (JVM: `ChapterDescriptor`). Progress is binary in the JVM
 *  (1.0 when the chapter has selected audio, else 0.0). */
data class OratureExportChapter(
    val sort: Int,
    val progress: Double,
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
    private val tempFiles: ITempFileProvider by inject()
    private val fileIO: IFileIOFactory by inject()

    private val _uiState = MutableStateFlow(OratureExportUiState())
    val uiState: StateFlow<OratureExportUiState> = _uiState.asStateFlow()

    private var workbook: Workbook? = null
    // The saved file, for "Show Location" (desktop only).
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
                // openWithChapters() dispatches to IO itself, so no withContext wrapper here.
                val opened = openWorkbook.openWithChapters(workbookDescriptorId)
                val wb = opened.workbook
                workbook = wb
                val chapters = opened.chapters.map { chapter ->
                    // JVM: progress is 1.0 when the chapter has selected audio, else 0.0.
                    OratureExportChapter(
                        sort = chapter.sort,
                        progress = if (opened.completedByChapterSort[chapter.sort] == true) 1.0 else 0.0,
                        selectable = false,
                        selected = false
                    )
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

    /** JVM onSelectExportType selectability rule: Backup includes any chapter with progress > 0;
     *  every other type only fully-complete chapters (progress == 1.0). Selectable rows start
     *  pre-selected. */
    private fun applySelectability(
        chapters: List<OratureExportChapter>,
        type: ExportType
    ): List<OratureExportChapter> = chapters.map { ch ->
        val selectable = if (type == ExportType.BACKUP) ch.progress > 0.0 else ch.progress >= 1.0
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

    /** Name to seed the save dialog with (mirrors `RCProjectExporter.makeExportFilename`); FileKit adds the extension. */
    fun suggestedExportName(): String {
        val wb = workbook ?: return "export"
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        return "${wb.target.language.slug}-${wb.target.resourceMetadata.identifier}-${wb.target.slug}-$timestamp"
    }

    /** Listen exports a folder of mp3s, which is zipped for the save dialog; the others are one RC archive. */
    fun exportExtension(): String = if (_uiState.value.selectedType == ExportType.LISTEN) "zip" else "orature"

    /**
     * Run the export into a temp dir, then copy the result to [destination] (JVM: exportWorkbook).
     * A save-dialog file, not a picked folder: on Android a picked folder is a SAF tree URI that
     * java.io.File can't write into. Backup ignores chapter selection.
     */
    fun export(destination: PlatformFile) {
        val wb = workbook ?: return
        val type = _uiState.value.selectedType
        val chapters = selectedChapters().takeIf { type != ExportType.BACKUP && it.isNotEmpty() }
        exportedFile = null
        _uiState.value = _uiState.value.copy(progress = 0f, error = null)
        launchLogged {
            val ok = withContext(Dispatchers.IO) {
                withTempDir { tempDir ->
                    runCatching {
                        val output = exportInto(tempDir, wb, type, chapters)
                            ?: return@runCatching false
                        destination.write(PlatformFile(asSingleFile(output)))
                        true
                    }
                        .onFailure { logFailure("exporting the project", it) }
                        .getOrDefault(false)
                }
            }
            // Only desktop has a real path to reveal; on Android the destination is a content URI.
            if (ok && canOpenInFileManager()) exportedFile = File(destination.path)
            _uiState.value = if (ok) {
                _uiState.value.copy(progress = null, done = true)
            } else {
                _uiState.value.copy(progress = null, error = "export_failed")
            }
        }
    }

    /**
     * Runs the exporter into [dir], reporting progress;
     * returns what it produced, or null on failure.
     */
    private suspend fun exportInto(
        dir: File,
        wb: Workbook,
        type: ExportType,
        chapters: List<Int>?
    ): File? {
        var produced: File? = null
        val callback = object : ProjectExporterCallback {
            override fun onNotifySuccess(project: Collection, file: File) { produced = file }
            override fun onError(project: Collection) {}
            override fun onNotifyProgress(percent: Double, messageKey: String?) {
                _uiState.value = _uiState.value.copy(progress = (percent / 100.0).toFloat()
                    .coerceIn(0f, 1f))
            }
        }
        val result = exporterFor(type)
            .export(dir, wb, callback, chapters
            ?.let { ExportOptions(it) }).await()
        return produced?.takeIf { result == ExportResult.SUCCESS && it.exists() }
    }

    /**
     * The save dialog takes one file,
     * so a folder result (Listen's mp3s) is zipped beside itself.
     */
    private fun asSingleFile(output: File): File {
        if (!output.isDirectory) return output
        return File(output.parentFile, "${output.name}.zip").also { zip ->
            fileIO.newFileWriter(zip).use { it.copyDirectory(output, output.name) }
        }
    }

    private inline fun <T> withTempDir(block: (File) -> T): T {
        val dir = File(tempFiles.tempDirectory, "export-${UUID.randomUUID()}").apply { mkdirs() }
        return try { block(dir) } finally { dir.deleteRecursively() }
    }

    fun acknowledgeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
