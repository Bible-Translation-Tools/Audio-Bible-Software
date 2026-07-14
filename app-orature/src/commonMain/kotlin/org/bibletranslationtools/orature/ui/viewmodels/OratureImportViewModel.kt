package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.importFailed
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
import java.io.File

/** Import lifecycle for the source-audio import flow (JVM: import progress + conflict + result). */
sealed interface OratureImportState {
    data object Idle : OratureImportState
    data class InProgress(val percent: Double = 0.0) : OratureImportState
    /** The importer found the same source with a different version/versification and needs the
     *  user to confirm overwriting the existing source (JVM: ExistingSourceImporter user input). */
    data object ConflictPrompt : OratureImportState
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

    private val importProjectUseCase: ImportProjectUseCase by inject()
    private val directoryProvider: IDirectoryProvider by inject()

    private val _importState = MutableStateFlow<OratureImportState>(OratureImportState.Idle)
    val importState: StateFlow<OratureImportState> = _importState.asStateFlow()

    // Resolved by the UI's overwrite/cancel dialog; unblocks the importer waiting on user input.
    private var conflictSubject: SingleSubject<ImportOptions>? = null

    fun importFile(platformFile: PlatformFile) {
        val s = _importState.value
        if (s is OratureImportState.InProgress || s is OratureImportState.ConflictPrompt) return
        _importState.value = OratureImportState.InProgress()
        viewModelScope.launch {
            var staged: File? = null
            try {
                val callback = buildCallback()
                val result = withContext(Dispatchers.IO) {
                    val ext = platformFile.name.substringAfterLast('.', "").lowercase().ifEmpty { "zip" }
                    val tmp = File.createTempFile("import_", ".$ext", directoryProvider.tempDirectory)
                    tmp.writeBytes(platformFile.readBytes())
                    staged = tmp
                    importProjectUseCase.import(tmp, callback).await()
                }
                _importState.value = when (result) {
                    ImportResult.SUCCESS, ImportResult.ALREADY_EXISTS -> OratureImportState.Success
                    ImportResult.ABORTED -> OratureImportState.Idle
                    else -> OratureImportState.Error(getString(Res.string.importFailed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importState.value = OratureImportState.Error(e.message ?: getString(Res.string.importFailed))
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
                _importState.value = OratureImportState.InProgress(percent ?: 0.0)
            }
        }

        override fun onNotifySuccess(language: String?, project: String?, workbookDescriptor: WorkbookDescriptor?) {}

        override fun onError(filePath: String) {}
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
}
