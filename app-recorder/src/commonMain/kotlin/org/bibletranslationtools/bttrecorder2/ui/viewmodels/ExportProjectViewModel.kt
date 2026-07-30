package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection as OratureCollection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.ProjectCompletionStatus
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportOptions
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportResult
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportType
import org.bibletranslationtools.otter.common.domain.project.exporter.IProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.ProjectExporterCallback
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.BackupProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.SourceProjectExporter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.export_error_project_not_found
import org.bibletranslationtools.shared.resources.export_error_load_chapters_failed
import org.bibletranslationtools.shared.resources.export_error_no_file
import org.bibletranslationtools.shared.resources.export_error_generic
import org.bibletranslationtools.shared.resources.export_error_not_initialized
import org.bibletranslationtools.shared.resources.export_error_no_audio
import org.bibletranslationtools.shared.resources.export_error_no_takes_selected
import org.bibletranslationtools.shared.resources.export_error_packaging
import java.io.File
import java.util.UUID

/**
 * Owns three phases of project export:
 *
 *   1. **Options selection** — [openOptions] populates [options] with the
 *      workbook's chapters and lets the user pick an [ExportType] and which
 *      chapters to include. Mirrors Orature's pre-export dialog.
 *   2. **Destination picking** — handled by the UI calling `FileKit.openFileSaver`
 *      after the user taps "Export" in the options dialog; the resulting
 *      [PlatformFile] is fed into [beginExport].
 *   3. **The actual export** — [beginExport] runs on [viewModelScope] backed by
 *      [Dispatchers.IO]. Progress lands in [state]; the produced file is
 *      streamed via [PlatformFile.write] into the user-chosen destination.
 *
 * Hosted as a Koin singleton (process-lifetime) so the ProjectManagementScreen
 * and the Recorder route can share the same instance — the latter reads
 * [exportingRoute] to block recording while a backup of that workbook is in
 * flight.
 */
class ExportProjectViewModel : ViewModel(), KoinComponent {

    private val backupExporter: BackupProjectExporter by inject()
    private val sourceExporter: SourceProjectExporter by inject()
    private val directoryProvider: IDirectoryProvider by inject()
    private val workbookRepository: IWorkbookRepository by inject()
    private val completionStatus: ProjectCompletionStatus by inject()

    private val _options = MutableStateFlow<ExportOptionsState>(ExportOptionsState.Closed)
    val options: StateFlow<ExportOptionsState> = _options.asStateFlow()

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private val _exportingWorkbookId = MutableStateFlow<Int?>(null)
    val exportingWorkbookId: StateFlow<Int?> = _exportingWorkbookId.asStateFlow()

    private val _exportingRoute = MutableStateFlow<Pair<Int, Int>?>(null)
    /**
     * `(sourceCollectionId, targetCollectionId)` of the workbook currently
     * being exported, or null if no export is in progress. The Recorder route
     * compares its own ids against this to block recording during export.
     */
    val exportingRoute: StateFlow<Pair<Int, Int>?> = _exportingRoute.asStateFlow()

    private var exportJob: Job? = null
    private var loadOptionsJob: Job? = null

    init {
        // Sweep orphaned temp dirs from prior killed exports.
        launchLogged(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val staleAfterMs = 60L * 60L * 1000L
                directoryProvider.tempDirectory
                    .listFiles { f -> f.isDirectory && f.name.startsWith("export-") }
                    ?.filter { now - it.lastModified() > staleAfterMs }
                    ?.forEach { it.deleteRecursively() }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Options dialog
    // -------------------------------------------------------------------------

    /**
     * Begin loading the export options dialog for [descriptor]. Sets the state
     * to [ExportOptionsState.Loading] immediately, then fans out to compute
     * per-chapter progress on a background dispatcher. When loading finishes,
     * the state transitions to [ExportOptionsState.Ready] with the chapter
     * list and the default selection (all "selectable" chapters checked).
     */
    fun openOptions(descriptor: WorkbookDescriptor) {
        loadOptionsJob?.cancel()
        _options.value = ExportOptionsState.Loading(descriptor)

        loadOptionsJob = launchLogged(Dispatchers.IO) {
            try {
                val workbook = workbookRepository.get(
                    descriptor.sourceCollection,
                    descriptor.targetCollection
                ) ?: run {
                    _options.value = ExportOptionsState.Error(
                        descriptor,
                        getString(Res.string.export_error_project_not_found)
                    )
                    return@launchLogged
                }

                val chapters = workbook.target.chapters.toList().blockingGet()
                val chapterDescriptors = chapters.map { chapter ->
                    val progress = when {
                        chapter.hasSelectedAudio() -> 1.0
                        else -> completionStatus.getChapterTranslationProgress(chapter)
                    }
                    ExportChapter(
                        sort = chapter.sort,
                        title = chapter.title,
                        progress = progress.toFloat().coerceIn(0f, 1f)
                    )
                }.sortedBy { it.sort }

                val defaultType = ExportType.BACKUP
                val initialSelection = chapterDescriptors
                    .filter { isChapterSelectable(it, defaultType) }
                    .map { it.sort }
                    .toSet()

                _options.value = ExportOptionsState.Ready(
                    descriptor = descriptor,
                    chapters = chapterDescriptors,
                    type = defaultType,
                    selectedChapterSorts = initialSelection
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading export options", e)
                _options.value = ExportOptionsState.Error(
                    descriptor,
                    e.message ?: getString(Res.string.export_error_load_chapters_failed)
                )
            }
        }
    }

    fun closeOptions() {
        loadOptionsJob?.cancel()
        loadOptionsJob = null
        _options.value = ExportOptionsState.Closed
    }

    fun setExportType(type: ExportType) {
        val current = _options.value as? ExportOptionsState.Ready ?: return
        // When the type changes, drop the old selection and pre-select every
        // chapter that's selectable under the new type — matches Orature's
        // behavior so users don't have to manually re-check chapters after a
        // type switch.
        val newSelection = current.chapters
            .filter { isChapterSelectable(it, type) }
            .map { it.sort }
            .toSet()
        _options.value = current.copy(type = type, selectedChapterSorts = newSelection)
    }

    fun toggleChapter(sort: Int) {
        val current = _options.value as? ExportOptionsState.Ready ?: return
        val chapter = current.chapters.firstOrNull { it.sort == sort } ?: return
        if (!isChapterSelectable(chapter, current.type)) return // non-selectable, ignore tap

        val newSelection = if (sort in current.selectedChapterSorts) {
            current.selectedChapterSorts - sort
        } else {
            current.selectedChapterSorts + sort
        }
        _options.value = current.copy(selectedChapterSorts = newSelection)
    }

    fun selectAllChapters() {
        val current = _options.value as? ExportOptionsState.Ready ?: return
        _options.value = current.copy(
            selectedChapterSorts = current.chapters
                .filter { isChapterSelectable(it, current.type) }
                .map { it.sort }
                .toSet()
        )
    }

    fun deselectAllChapters() {
        val current = _options.value as? ExportOptionsState.Ready ?: return
        _options.value = current.copy(selectedChapterSorts = emptySet())
    }

    /**
     * For BACKUP, any chapter with at least one selected take is fair game.
     * For SOURCE_AUDIO (and other "publish-style" types), only fully-complete
     * chapters can be exported — matches Orature's `onSelectExportType` logic
     * in `ExportProjectDialog`.
     */
    private fun isChapterSelectable(chapter: ExportChapter, type: ExportType): Boolean {
        return when (type) {
            ExportType.BACKUP -> chapter.progress > 0f
            else -> chapter.progress >= 1f
        }
    }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    /**
     * Triggered after the user has picked options AND a destination via the
     * file saver. Snapshots the current Ready options, closes the dialog,
     * and kicks off the actual export.
     */
    fun beginExport(destination: PlatformFile) {
        val current = _options.value as? ExportOptionsState.Ready ?: return
        if (_state.value is ExportState.InProgress) return

        val descriptor = current.descriptor
        val type = current.type
        val chapters = current.selectedChapterSorts.sorted().takeIf { it.isNotEmpty() }

        _options.value = ExportOptionsState.Closed
        _state.value = ExportState.InProgress(progress = 0f, statusKey = null)
        _exportingWorkbookId.value = descriptor.id
        _exportingRoute.value = descriptor.sourceCollection.id to descriptor.targetCollection.id

        exportJob = launchLogged(Dispatchers.IO) {
            var tempDir: File? = null
            var producedFile: File? = null
            try {
                val workbook = workbookRepository.get(
                    descriptor.sourceCollection,
                    descriptor.targetCollection
                ) ?: run {
                    _state.value = ExportState.Error(getString(Res.string.export_error_project_not_found))
                    return@launchLogged
                }

                // Projects created through the in-app wizard never have their
                // on-disk resource container initialized — that only happens
                // during the version-gated startup migration (for projects that
                // predate this install) or during import. Without it,
                // BackupProjectExporter.isInitialized() is false and the export
                // bails out with a silent FAILURE. Repair it here so export works
                // for any project regardless of how it was created. Idempotent:
                // initializeResourceContainerInDir(overwrite = false) is a no-op
                // when the container is already valid.
                ensureProjectInitialized(workbook, descriptor.mode)

                tempDir = File(directoryProvider.tempDirectory, "export-${UUID.randomUUID()}")
                    .apply { mkdirs() }

                val callback = object : ProjectExporterCallback {
                    override fun onNotifyProgress(percent: Double, messageKey: String?) {
                        _state.update {
                            if (it is ExportState.InProgress) {
                                it.copy(
                                    progress = (percent / 100.0).toFloat().coerceIn(0f, 1f),
                                    statusKey = messageKey ?: it.statusKey
                                )
                            } else it
                        }
                    }

                    override fun onNotifySuccess(project: OratureCollection, file: File) {
                        producedFile = file
                    }

                    override fun onError(project: OratureCollection) {
                        // Surfaced via the export Single's error path below.
                    }
                }

                val exporter: IProjectExporter = exporterFor(type)
                val exportOptions = chapters?.let { ExportOptions(it) }

                val result = exporter
                    .export(tempDir, workbook, callback, exportOptions)
                    .await()

                if (result != ExportResult.SUCCESS) {
                    // The Orature exporters swallow the underlying cause
                    // (onErrorReturnItem(FAILURE)). Inspect on-disk + workbook
                    // state to produce an actionable message. A desktop run from
                    // a terminal will also now print the real stack trace via the
                    // slf4j-simple binding.
                    _state.value = ExportState.Error(diagnoseFailure(workbook))
                    return@launchLogged
                }

                val produced = producedFile ?: tempDir.listFiles()
                    ?.firstOrNull { it.extension.equals("orature", ignoreCase = true) }
                if (produced == null || !produced.exists()) {
                    _state.value = ExportState.Error(getString(Res.string.export_error_no_file))
                    return@launchLogged
                }

                withContext(Dispatchers.IO) {
                    destination.write(PlatformFile(produced))
                }

                _state.value = ExportState.Success(
                    producedName = produced.name,
                    destinationName = destination.name
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("exporting the project", e)
                _state.value = ExportState.Error(e.message ?: getString(Res.string.export_error_generic))
            } finally {
                _exportingWorkbookId.value = null
                _exportingRoute.value = null
                tempDir?.let { runCatching { it.deleteRecursively() } }
            }
        }
    }

    /**
     * Builds a human-readable explanation after the exporter returns a swallowed
     * FAILURE, by re-checking the conditions the exporter cares about. Best-effort;
     * each probe is independently guarded so a probe failure can't mask the others.
     */
    private suspend fun diagnoseFailure(workbook: Workbook): String {
        val accessor = workbook.projectFilesAccessor

        val initialized = runCatching { accessor.isInitialized() }.getOrDefault(false)
        if (!initialized) {
            return getString(Res.string.export_error_not_initialized)
        }

        // Count audio files actually present under the project's audio directory.
        val audioFileCount = runCatching {
            accessor.audioDir
                .walkTopDown()
                .count { it.isFile && it.extension.lowercase() in setOf("wav", "mp3") }
        }.getOrDefault(-1)

        // Count selected takes across chapters + chunks.
        val selectedTakeCount = runCatching {
            var count = 0
            val chapters = workbook.target.chapters.toList().blockingGet()
            chapters.forEach { chapter ->
                if (chapter.audio.getSelectedTake() != null) count++
                val chunks = runCatching {
                    chapter.observableChunks.filter { it.isNotEmpty() }.blockingFirst(emptyList())
                }.getOrDefault(emptyList())
                chunks.forEach { chunk ->
                    if (chunk.audio.getSelectedTake() != null) count++
                }
            }
            count
        }.getOrDefault(-1)

        return when {
            audioFileCount == 0 ->
                getString(Res.string.export_error_no_audio)
            selectedTakeCount == 0 ->
                getString(Res.string.export_error_no_takes_selected)
            else ->
                getString(Res.string.export_error_packaging, audioFileCount, selectedTakeCount)
        }
    }

    /**
     * Brings an in-app-created project's on-disk files up to what the Orature
     * exporters expect. Projects made through the wizard skip the artifacts that
     * Orature normally writes during its `InitializeProjects` migration / import,
     * so we backfill them here. Each artifact is checked independently and
     * idempotently — a project can have a valid RC dir but still be missing
     * `project_mode.json` (the original failure: BackupProjectExporter.copyProjectModeFile
     * threw FileNotFoundException at 99%).
     */
    private fun ensureProjectInitialized(workbook: Workbook, mode: ProjectMode) {
        val accessor = workbook.projectFilesAccessor

        // 1. The resource container in the project dir (isInitialized precondition).
        if (!accessor.isInitialized()) {
            accessor.initializeResourceContainerInDir(overwrite = false)
            val linkedResource = workbook.source.linkedResources
                .firstOrNull { it.identifier == workbook.target.resourceMetadata.identifier }
            accessor.copySourceFiles(linkedResource)
            accessor.writeSelectedTakesFile(workbook, isBook = true)
        }

        // 2. project_mode.json — required by BackupProjectExporter.copyProjectModeFile,
        // never written by the in-app creation flow. Independent of the RC dir
        // check above (a project can be "initialized" yet still lack this file).
        val modeFile = accessor.projectDir.resolve(RcConstants.PROJECT_MODE_FILE)
        if (!modeFile.exists() || modeFile.length() == 0L) {
            accessor.setProjectMode(mode)
        }
    }

    fun cancel() {
        exportJob?.cancel()
        exportJob = null
        _exportingWorkbookId.value = null
        _exportingRoute.value = null
        _state.value = ExportState.Idle
    }

    fun acknowledge() {
        if (_state.value is ExportState.Success || _state.value is ExportState.Error) {
            _state.value = ExportState.Idle
        }
    }

    /**
     * Suggested filename to seed the save dialog with. Mirrors Orature's
     * `RCProjectExporter.makeExportFilename`: `<lang>-<resource>-<book>-<timestamp>`.
     * The extension is appended by FileKit.
     */
    fun suggestedExportName(descriptor: WorkbookDescriptor): String {
        val lang = descriptor.targetLanguage.slug
        val resource = descriptor.targetCollection.resourceContainer?.identifier ?: "rc"
        val book = descriptor.targetCollection.slug
        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        return "$lang-$resource-$book-$timestamp"
    }

    /** Extension used in the save dialog for the current options state. */
    fun fileExtensionForType(type: ExportType): String = when (type) {
        // Both BACKUP and SOURCE_AUDIO produce a ResourceContainer renamed to
        // .orature on export — see RCProjectExporter.restoreFileExtension.
        ExportType.BACKUP, ExportType.SOURCE_AUDIO -> "orature"
        else -> "orature"
    }

    private fun exporterFor(type: ExportType): IProjectExporter = when (type) {
        ExportType.BACKUP -> backupExporter
        ExportType.SOURCE_AUDIO, ExportType.PUBLISH -> sourceExporter
        else -> backupExporter
    }

    override fun onCleared() {
        super.onCleared()
        exportJob?.cancel()
        loadOptionsJob?.cancel()
        exportJob = null
        loadOptionsJob = null
    }
}

sealed interface ExportState {
    data object Idle : ExportState
    data class InProgress(val progress: Float, val statusKey: String?) : ExportState
    data class Success(val producedName: String, val destinationName: String) : ExportState
    data class Error(val message: String) : ExportState
}

sealed interface ExportOptionsState {
    data object Closed : ExportOptionsState
    data class Loading(val descriptor: WorkbookDescriptor) : ExportOptionsState
    data class Ready(
        val descriptor: WorkbookDescriptor,
        val chapters: List<ExportChapter>,
        val type: ExportType,
        val selectedChapterSorts: Set<Int>
    ) : ExportOptionsState {
        val canExport: Boolean get() = selectedChapterSorts.isNotEmpty()
    }
    data class Error(val descriptor: WorkbookDescriptor, val message: String) : ExportOptionsState
}

data class ExportChapter(
    val sort: Int,
    val title: String,
    val progress: Float
)
