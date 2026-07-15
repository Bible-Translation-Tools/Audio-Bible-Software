package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.path
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.backup
import org.bibletranslationtools.orature.resources.chapter
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.estimatedFileSize
import org.bibletranslationtools.orature.resources.exportProject
import org.bibletranslationtools.orature.resources.listen
import org.bibletranslationtools.orature.resources.sourceAudio
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportType
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureExportProjectViewModel
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * The project-export modal (JVM: `ExportProjectDialog`). Choose an export type (Backup / Source Audio
 * / Listen), pick which chapters to include (Backup includes everything), see an estimated size, then
 * choose an output directory to export into with a progress bar.
 */
@Composable
fun OratureExportProjectDialog(
    workbookDescriptorId: Int,
    onDismiss: () -> Unit,
    onFinished: (success: Boolean, location: File?) -> Unit
) {
    val vm = viewModel(key = "export-$workbookDescriptorId") { OratureExportProjectViewModel(workbookDescriptorId) }
    val state by vm.uiState.collectAsState()

    val dirPicker = rememberDirectoryPickerLauncher { dir ->
        dir?.let { vm.export(File(it.path)) }
    }

    // Report the terminal result so the home can toast success/failure and dismiss (JVM:
    // WorkbookExportFinishEvent → createExportNotification).
    LaunchedEffect(state.done, state.error) {
        if (state.done) onFinished(true, vm.exportedLocation())
        else if (state.error != null) onFinished(false, null)
    }

    val exporting = state.progress != null
    Dialog(onDismissRequest = { if (!exporting) onDismiss() }) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Header
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.exportProject) +
                            (state.bookTitle.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, enabled = !exporting) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.close), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                if (state.isLoading) {
                    Row(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = OratureColors.Primary)
                    }
                } else {
                    // Export type
                    ExportTypeOption(stringResource(Res.string.backup), state.selectedType == ExportType.BACKUP) { vm.selectType(ExportType.BACKUP) }
                    ExportTypeOption(stringResource(Res.string.sourceAudio), state.selectedType == ExportType.SOURCE_AUDIO) { vm.selectType(ExportType.SOURCE_AUDIO) }
                    ExportTypeOption(stringResource(Res.string.listen), state.selectedType == ExportType.LISTEN) { vm.selectType(ExportType.LISTEN) }

                    // Chapter selection (Backup exports everything, so selection is disabled there).
                    val chapterSelectable = state.selectedType != ExportType.BACKUP
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        for (ch in state.chapters) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = ch.selected,
                                    onCheckedChange = { vm.toggleChapter(ch.sort) },
                                    enabled = chapterSelectable && ch.selectable
                                )
                                Text(
                                    "${stringResource(Res.string.chapter)} ${ch.sort}",
                                    color = if (ch.selectable) OratureColors.RegularText else OratureColors.NoteText
                                )
                            }
                        }
                    }

                    Text(
                        stringResource(Res.string.estimatedFileSize, formatSize(state.estimatedSizeBytes)),
                        fontSize = 14.sp,
                        color = OratureColors.NoteText
                    )

                    if (exporting) {
                        LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = { vm.acknowledgeError(); dirPicker.launch() },
                                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                            ) { Text(stringResource(Res.string.exportProject)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportTypeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, color = OratureColors.RegularText, modifier = Modifier.padding(start = 4.dp))
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb < 1) "< 1 MB" else "${mb.toLong()} MB"
}
