package org.bibletranslationtools.orature.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import org.bibletranslationtools.orature.ui.viewmodels.OratureExportProjectViewModel
import java.io.File

/**
 * Headless "quick backup" flow (JVM: row menu's Backup → `WorkbookQuickBackupEvent` → choose a
 * directory → export straight into a Backup archive, with no options dialog — Backup ignores
 * chapter selection so there's nothing to configure). Renders no UI itself; it opens the OS
 * save dialog once the workbook loads and reports the result.
 */
@Composable
fun OratureQuickBackup(
    workbookDescriptorId: Int,
    onCancelled: () -> Unit,
    onFinished: (success: Boolean, location: File?) -> Unit
) {
    val vm = viewModel(key = "quick-backup-$workbookDescriptorId") { OratureExportProjectViewModel(workbookDescriptorId) }
    val state by vm.uiState.collectAsState()
    var destination by remember { mutableStateOf<PlatformFile?>(null) }
    var exportStarted by remember { mutableStateOf(false) }

    val saver = rememberFileSaverLauncher { picked ->
        if (picked == null) onCancelled() else destination = picked
    }
    // The suggested name needs the workbook, so open the save dialog once it has loaded
    // (selectedType defaults to ExportType.BACKUP already).
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && state.error == null) saver.launch(vm.suggestedExportName(), vm.exportExtension())
    }

    LaunchedEffect(destination) {
        val file = destination
        if (!exportStarted && file != null) {
            exportStarted = true
            vm.export(file)
        }
    }

    LaunchedEffect(state.done, state.error) {
        if (state.done) onFinished(true, vm.exportedLocation())
        else if (state.error != null) onFinished(false, null)
    }
}
