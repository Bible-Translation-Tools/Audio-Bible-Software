package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.reactivex.Single
import io.reactivex.subjects.SingleSubject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.importFailed
import org.bibletranslationtools.orature.resources.importFailedMessage
import org.bibletranslationtools.orature.resources.importProjectSuccessfulMessage
import org.bibletranslationtools.orature.resources.importSourceSuccessfulMessage
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.importer.ImportCallbackParameter
import org.bibletranslationtools.otter.common.domain.project.importer.ImportOptions
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import java.io.File

/** Import lifecycle for the source-audio import flow (JVM: import progress + conflict + result). */
sealed interface OratureImportState {
    data object Idle : OratureImportState
    /** [percent] 0..100; [stepKey] is the importer's current-step resource key (e.g. "mergingSource")
     *  shown in the progress dialog. */
    data class InProgress(val percent: Double = 0.0, val stepKey: String? = null) : OratureImportState
    /** The importer found the same source with a different version/versification and needs the
     *  user to confirm overwriting the existing source (JVM: ExistingSourceImporter user input). */
    data object ConflictPrompt : OratureImportState
    /** Terminal success — the message is shown as an app-root snackbar, not in the dialog. */
    data object Success : OratureImportState
    data class Error(val message: String) : OratureImportState
}

/**
 * Imports an Orature/RC/tstudio file (a source-audio project) into the app so a translation project
 * can gain source audio. Drives the shared [ImportProjectUseCase] WITH a [ProjectImporterCallback] so
 * it behaves like Orature: when the file matches an existing source with the same version/versification
 * the audio is MERGED into that source (no duplicate); when it differs the user is PROMPTED to overwrite
 * (rather than the no-callback default which silently forks/deletes). Reports progress + conflict.
 */
class OratureImportViewModel : ViewModel(), KoinComponent {

    private val logger = LoggerFactory.getLogger(OratureImportViewModel::class.java)

    private val importProjectUseCase: ImportProjectUseCase by inject()
    private val directoryProvider: IDirectoryProvider by inject()
    private val importEvents: OratureImportEvents by inject()

    private val _importState = MutableStateFlow<OratureImportState>(OratureImportState.Idle)
    val importState: StateFlow<OratureImportState> = _importState.asStateFlow()

    // Resolved by the UI's overwrite/cancel dialog; unblocks the importer waiting on user input.
    private var conflictSubject: SingleSubject<ImportOptions>? = null

    // Captured from the importer's success callback for the success message (project = book title,
    // language = anglicized language name).
    @Volatile
    private var successProject: String? = null
    @Volatile
    private var successLanguage: String? = null
    // The imported book (if any) for the success snackbar's "Open Book" action.
    @Volatile
    private var successBookId: Int? = null
    @Volatile
    private var successBookMode: org.bibletranslationtools.otter.common.data.primitives.ProjectMode? = null

    fun importFile(platformFile: PlatformFile) {
        val s = _importState.value
        if (s is OratureImportState.InProgress || s is OratureImportState.ConflictPrompt) return
        successProject = null
        successLanguage = null
        successBookId = null
        successBookMode = null
        _importState.value = OratureImportState.InProgress()
        launchLogged {
            var staged: File? = null
            try {
                logger.info("Importing ${platformFile.name}")
                val callback = buildCallback()
                val result = withContext(Dispatchers.IO) {
                    val ext = platformFile.name.substringAfterLast('.', "").lowercase().ifEmpty { "zip" }
                    val tmp = File.createTempFile("import_", ".$ext", directoryProvider.tempDirectory)
                    tmp.writeBytes(platformFile.readBytes())
                    staged = tmp
                    importProjectUseCase.import(tmp, callback).await()
                }
                logger.info("Import of ${platformFile.name} finished with result=$result")
                _importState.value = when (result) {
                    ImportResult.SUCCESS, ImportResult.ALREADY_EXISTS -> {
                        importEvents.notifyImported() // refresh the home project list
                        // Success message shows as an app-root snackbar (JVM notification), not in a
                        // dialog: "{project} ({language})…" for a book, or the source-only variant.
                        val message = if (successProject != null) {
                            getString(Res.string.importProjectSuccessfulMessage, successProject!!, successLanguage.orEmpty())
                        } else {
                            getString(Res.string.importSourceSuccessfulMessage, successLanguage.orEmpty())
                        }
                        importEvents.notify(
                            OratureImportNotification(
                                message = message,
                                workbookDescriptorId = successBookId,
                                mode = successBookMode
                            )
                        )
                        OratureImportState.Success
                    }
                    ImportResult.ABORTED -> OratureImportState.Idle
                    else -> {
                        logger.error("Import of ${platformFile.name} failed: $result")
                        // User-facing failure toast (JVM importFailedMessage); the specific cause is logged.
                        importEvents.notify(OratureImportNotification(getString(Res.string.importFailedMessage, platformFile.name)))
                        OratureImportState.Error(messageForResult(result))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error importing ${platformFile.name}", e)
                importEvents.notify(OratureImportNotification(getString(Res.string.importFailedMessage, platformFile.name)))
                _importState.value = OratureImportState.Error(
                    e.message?.takeIf { it.isNotBlank() } ?: getString(Res.string.importFailed)
                )
            } finally {
                staged?.let { runCatching { it.delete() } }
            }
        }
    }

    private fun buildCallback() = object : ProjectImporterCallback {
        override fun onRequestUserInput(): Single<ImportOptions> {
            val subject = SingleSubject.create<ImportOptions>()
            conflictSubject = subject
            _importState.value = OratureImportState.ConflictPrompt
            return subject
        }

        override fun onRequestUserInput(parameter: ImportCallbackParameter): Single<ImportOptions> =
            onRequestUserInput()

        override fun onNotifyProgress(localizeKey: String?, message: String?, percent: Double?) {
            if (_importState.value !is OratureImportState.ConflictPrompt) {
                val prev = _importState.value as? OratureImportState.InProgress
                _importState.value = OratureImportState.InProgress(
                    percent = percent ?: prev?.percent ?: 0.0,
                    // Keep showing the last step when a progress tick carries only a percent.
                    stepKey = localizeKey ?: prev?.stepKey
                )
            }
        }

        override fun onNotifySuccess(language: String?, project: String?, workbookDescriptor: WorkbookDescriptor?) {
            successLanguage = language
            successProject = project
            successBookId = workbookDescriptor?.id
            successBookMode = workbookDescriptor?.mode
        }

        override fun onError(filePath: String) {
            logger.error("Importer reported an error for: $filePath")
        }
    }

    /** A user-facing reason for a non-success [ImportResult] (falls back to the generic message). */
    private suspend fun messageForResult(result: ImportResult): String {
        val base = getString(Res.string.importFailed)
        return when (result) {
            ImportResult.INVALID_RC,
            ImportResult.INVALID_CONTENT -> "$base ${result.name}: the file's content is invalid or unreadable."
            ImportResult.UNSUPPORTED_CONTENT -> "$base ${result.name}: this file type isn't supported."
            ImportResult.LOAD_RC_ERROR -> "$base ${result.name}: the project could not be opened."
            else -> "$base (${result.name})"
        }
    }

    /** Answer the overwrite prompt (JVM: ConfirmDialog): true = overwrite existing source. */
    fun resolveConflict(overwrite: Boolean) {
        val subject = conflictSubject ?: return
        conflictSubject = null
        _importState.value = OratureImportState.InProgress()
        subject.onSuccess(ImportOptions(confirmed = overwrite))
    }

    fun acknowledge() {
        _importState.value = OratureImportState.Idle
    }

    /**
     * Reset to the initial drop-area state. Called when the dialog (re)opens so a persisted VM (the
     * store outlives the dialog's show/hide) doesn't show the previous run's success/error — the JVM
     * recreated a fresh progress dialog and reset its state on each import.
     */
    fun reset() {
        successProject = null
        successLanguage = null
        successBookId = null
        successBookMode = null
        conflictSubject = null
        _importState.value = OratureImportState.Idle
    }
}
