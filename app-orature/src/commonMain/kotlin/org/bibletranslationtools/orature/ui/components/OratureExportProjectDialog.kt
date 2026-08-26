package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import org.bibletranslationtools.orature.resources.bookNameExportTitle
import org.bibletranslationtools.orature.resources.chapter
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.estimatedFileSize
import org.bibletranslationtools.orature.resources.exportProject
import org.bibletranslationtools.orature.resources.listen
import org.bibletranslationtools.orature.resources.progress
import org.bibletranslationtools.orature.resources.publish
import org.bibletranslationtools.orature.resources.sourceAudio
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportType
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureExportChapter
import org.bibletranslationtools.orature.ui.viewmodels.OratureExportProjectViewModel
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * The project-export modal (JVM: `ExportProjectDialog`). A two-pane dialog: a left rail of
 * export-type cards (Backup / Source Audio / Listen / Publish) and a right chapter table (select-all
 * + per-chapter progress), with a footer showing the estimated size and an Export button. Backup
 * includes any chapter with audio; the other types only fully-complete chapters.
 *
 * (The JVM's 5th type, Burrito Wrapper, is intentionally omitted — its exporter isn't in :shared.)
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

    LaunchedEffect(state.done, state.error) {
        if (state.done) onFinished(true, vm.exportedLocation())
        else if (state.error != null) onFinished(false, null)
    }

    val exporting = state.progress != null
    Dialog(onDismissRequest = { if (!exporting) onDismiss() }) {
        Surface(
            modifier = Modifier.width(720.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                // ---- Header (JVM confirm-dialog__header) ----
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.bookNameExportTitle, state.bookTitle),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = OratureColors.RegularText,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, enabled = !exporting) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(Res.string.close),
                            tint = OratureColors.RegularText
                        )
                    }
                }
                HorizontalDivider(color = OratureColors.SurfaceTertiary)

                if (state.isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = OratureColors.Primary)
                    }
                } else {
                    // ---- Body: left type rail | right chapter table ----
                    Row(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                        Column(
                            modifier = Modifier
                                .width(240.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            ExportTypeCard(stringResource(Res.string.backup), state.selectedType == ExportType.BACKUP) { vm.selectType(ExportType.BACKUP) }
                            ExportTypeCard(stringResource(Res.string.sourceAudio), state.selectedType == ExportType.SOURCE_AUDIO) { vm.selectType(ExportType.SOURCE_AUDIO) }
                            ExportTypeCard(stringResource(Res.string.listen), state.selectedType == ExportType.LISTEN) { vm.selectType(ExportType.LISTEN) }
                            ExportTypeCard(stringResource(Res.string.publish), state.selectedType == ExportType.PUBLISH) { vm.selectType(ExportType.PUBLISH) }
                        }
                        VerticalDivider(color = OratureColors.SurfaceTertiary)
                        ChapterTable(
                            chapters = state.chapters,
                            onToggleChapter = vm::toggleChapter,
                            onToggleAll = vm::toggleAll,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    HorizontalDivider(color = OratureColors.SurfaceTertiary)

                    // ---- Footer (estimated size + Export) ----
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                        if (exporting) {
                            LinearProgressIndicator(
                                progress = { state.progress ?: 0f },
                                modifier = Modifier.fillMaxWidth(),
                                color = OratureColors.Primary
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(Res.string.estimatedFileSize, formatSize(state.estimatedSizeBytes)),
                                    fontSize = 14.sp,
                                    color = OratureColors.NoteText,
                                    modifier = Modifier.weight(1f)
                                )
                                val anySelected = state.chapters.any { it.selected }
                                Button(
                                    onClick = { vm.acknowledgeError(); dirPicker.launch() },
                                    enabled = anySelected,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                                ) {
                                    Icon(Icons.Filled.Publish, contentDescription = null, modifier = Modifier.width(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(Res.string.exportProject))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Left-rail export-type card (JVM: `cardRadioButton`). Selected = primary-light fill. */
@Composable
private fun ExportTypeCard(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .background(if (selected) OratureColors.PrimaryLight else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = OratureColors.Primary,
                unselectedColor = OratureColors.NoteText
            )
        )
        Text(
            label,
            color = OratureColors.RegularText,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** The right-pane chapter table (JVM: `ExportProjectTableView`): select-all header + per-chapter
 *  checkbox, chapter number, and a progress bar. */
@Composable
private fun ChapterTable(
    chapters: List<OratureExportChapter>,
    onToggleChapter: (Int) -> Unit,
    onToggleAll: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        val selectable = chapters.filter { it.selectable }
        val allSelected = selectable.isNotEmpty() && selectable.all { it.selected }
        Row(
            modifier = Modifier.fillMaxWidth().background(OratureColors.TableHeaderBackground)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = { onToggleAll(it) },
                enabled = selectable.isNotEmpty()
            )
            Text(
                stringResource(Res.string.chapter),
                fontWeight = FontWeight.Bold,
                color = OratureColors.RegularText,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            Text(
                stringResource(Res.string.progress),
                fontWeight = FontWeight.Bold,
                color = OratureColors.RegularText,
                modifier = Modifier.width(120.dp)
            )
        }
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            for (ch in chapters) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = ch.selected,
                        onCheckedChange = { onToggleChapter(ch.sort) },
                        enabled = ch.selectable
                    )
                    Text(
                        "${ch.sort}",
                        color = if (ch.selectable) OratureColors.RegularText else OratureColors.NoteText,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    LinearProgressIndicator(
                        progress = { ch.progress.toFloat() },
                        modifier = Modifier.width(120.dp),
                        color = OratureColors.StatusComplete,
                        trackColor = OratureColors.SurfaceTertiary
                    )
                }
            }
        }
    }
}

/**
 * The megabyte COUNT only — the unit belongs to the `estimatedFileSize` string, which is where a
 * translator can localize it (ru "МБ", fr "Mo"). This used to append " MB" itself while the
 * template appended it too, so the dialog read "Estimated File Size: 8 MB MB".
 */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb < 1) "< 1" else "${mb.toLong()}"
}
