package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
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
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/** Import lifecycle for the source-audio import flow (JVM: import progress + result notification). */
sealed interface OratureImportState {
    data object Idle : OratureImportState
    data object InProgress : OratureImportState
    data object Success : OratureImportState
    data class Error(val message: String) : OratureImportState
}

/**
 * Imports an Orature/RC/tstudio file (a source-audio project) into the app so a translation
 * project can gain source audio. Ports the recorder's `ProjectManagementViewModel.importProject`
 * path over the shared [ImportProjectUseCase]: stage the picked file to a temp file, run the
 * importer, surface progress/result. (Full Phase 9 — conflict dialogs, all formats, the AddFiles
 * view — builds on this.)
 */
class OratureImportViewModel : ViewModel(), KoinComponent {

    private val importProjectUseCase: ImportProjectUseCase by inject()
    private val directoryProvider: IDirectoryProvider by inject()

    private val _importState = MutableStateFlow<OratureImportState>(OratureImportState.Idle)
    val importState: StateFlow<OratureImportState> = _importState.asStateFlow()

    fun importFile(platformFile: PlatformFile) {
        if (_importState.value is OratureImportState.InProgress) return
        _importState.value = OratureImportState.InProgress
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
                _importState.value = when (result) {
                    ImportResult.SUCCESS, ImportResult.ALREADY_EXISTS -> OratureImportState.Success
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

    fun acknowledge() {
        _importState.value = OratureImportState.Idle
    }
}
