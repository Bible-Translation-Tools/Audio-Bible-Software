package org.bibletranslationtools.orature.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.path
import org.bibletranslationtools.orature.ui.viewmodels.OratureExportProjectViewModel
import java.io.File

/**
 * Headless "quick backup" flow (JVM: row menu's Backup → `WorkbookQuickBackupEvent` → choose a
 * directory → export straight into a Backup archive, with no options dialog — Backup ignores
 * chapter selection so there's nothing to configure). Renders no UI itself; it opens the OS
 * directory picker on first composition and reports the result.
 */
@Composable
fun OratureQuickBackup(
    workbookDescriptorId: Int,
    onCancelled: () -> Unit,
    onFinished: (success: Boolean, location: File?) -> Unit
) {
    val vm = viewModel(key = "quick-backup-$workbookDescriptorId") { OratureExportProjectViewModel(workbookDescriptorId) }
    val state by vm.uiState.collectAsState()
    var directory by remember { mutableStateOf<File?>(null) }
    var exportStarted by remember { mutableStateOf(false) }

    val dirPicker = rememberDirectoryPickerLauncher { picked ->
        if (picked == null) onCancelled() else directory = File(picked.path)
    }
    LaunchedEffect(Unit) { dirPicker.launch() }

    // Kick off the export once the workbook has loaded (state.isLoading false — selectedType
    // defaults to ExportType.BACKUP already) AND a directory has been chosen.
    LaunchedEffect(state.isLoading, directory) {
        val dir = directory
        if (!exportStarted && !state.isLoading && dir != null) {
            exportStarted = true
            vm.export(dir)
        }
    }

    LaunchedEffect(state.done, state.error) {
        if (state.done) onFinished(true, vm.exportedLocation())
        else if (state.error != null) onFinished(false, null)
    }
}
