package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.cancel
import org.bibletranslationtools.orature.resources.choose_file
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.`continue`
import org.bibletranslationtools.orature.resources.dragAndDropDescription
import org.bibletranslationtools.orature.resources.dragToImport
import org.bibletranslationtools.orature.resources.importFailed
import org.bibletranslationtools.orature.resources.importProjectSuccessfulMessage
import org.bibletranslationtools.orature.resources.import_projects
import org.bibletranslationtools.orature.resources.overridingSource
import org.bibletranslationtools.orature.resources.warning
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureImportState
import org.bibletranslationtools.orature.ui.viewmodels.OratureImportViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * The project-import modal (JVM: `ImportProjectDialog`), opened from the home page's import button.
 * Pick an Orature/RC/Burrito/.tstudio file to import a project; shows progress, a merge/overwrite
 * conflict prompt, and success/error. On success the home project list refreshes (via
 * [OratureImportViewModel] → OratureImportEvents). Drag-and-drop isn't wired (Compose Desktop drop
 * targets are a follow-up); Choose File covers the flow.
 */
@Composable
fun OratureImportProjectDialog(onDismiss: () -> Unit) {
    val importVm = viewModel { OratureImportViewModel() }
    val importState by importVm.importState.collectAsState()

    val picker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("orature", "zip", "tstudio")),
        mode = FileKitMode.Single
    ) { file -> file?.let(importVm::importFile) }

    val inProgress = importState is OratureImportState.InProgress
    Dialog(onDismissRequest = { if (!inProgress) onDismiss() }) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // ── Header ─────────────────────────────────────────────────
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.import_projects),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, enabled = !inProgress) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.close), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Text(
                    text = stringResource(Res.string.dragAndDropDescription),
                    fontSize = 15.sp,
                    color = OratureColors.NoteText
                )

                // ── Drop area / status ─────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, tint = OratureColors.Primary, modifier = Modifier.height(44.dp))

                    when (val s = importState) {
                        is OratureImportState.InProgress -> {
                            Text(stringResource(Res.string.import_projects), color = OratureColors.RegularText)
                            LinearProgressIndicator(
                                progress = { (s.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        is OratureImportState.Success -> {
                            Text(stringResource(Res.string.importProjectSuccessfulMessage), color = OratureColors.RegularText)
                            Button(
                                onClick = { importVm.acknowledge(); onDismiss() },
                                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                            ) { Text(stringResource(Res.string.close)) }
                        }
                        else -> {
                            Text(stringResource(Res.string.dragToImport), color = OratureColors.NoteText)
                            Button(
                                onClick = { picker.launch() },
                                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                            ) { Text(stringResource(Res.string.choose_file)) }
                        }
                    }
                }
            }
        }
    }

    // Conflict: the file matches an existing source with a different version/versification
    // (JVM: ExistingSourceImporter onRequestUserInput) — overwrite or cancel.
    if (importState is OratureImportState.ConflictPrompt) {
        AlertDialog(
            onDismissRequest = { importVm.resolveConflict(false) },
            title = { Text(stringResource(Res.string.warning)) },
            text = { Text(stringResource(Res.string.overridingSource)) },
            confirmButton = {
                TextButton(onClick = { importVm.resolveConflict(true) }) { Text(stringResource(Res.string.`continue`)) }
            },
            dismissButton = {
                TextButton(onClick = { importVm.resolveConflict(false) }) { Text(stringResource(Res.string.cancel)) }
            }
        )
    }

    (importState as? OratureImportState.Error)?.let { err ->
        AlertDialog(
            onDismissRequest = importVm::acknowledge,
            title = { Text(stringResource(Res.string.import_projects)) },
            text = { Text(err.message.ifEmpty { stringResource(Res.string.importFailed) }) },
            confirmButton = { TextButton(onClick = importVm::acknowledge) { Text(stringResource(Res.string.close)) } }
        )
    }
}
