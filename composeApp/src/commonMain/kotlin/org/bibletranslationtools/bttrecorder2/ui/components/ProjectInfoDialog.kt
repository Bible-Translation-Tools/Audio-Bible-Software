package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bibletranslationtools.bttrecorder2.domain.SourceAudioImporter
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.koin.mp.KoinPlatform.getKoin
import org.jetbrains.compose.resources.stringResource
import btt_recorder2.composeapp.generated.resources.Res
import btt_recorder2.composeapp.generated.resources.action_cancel
import btt_recorder2.composeapp.generated.resources.action_close
import btt_recorder2.composeapp.generated.resources.action_delete
import btt_recorder2.composeapp.generated.resources.action_dismiss
import btt_recorder2.composeapp.generated.resources.cd_back_up_project
import btt_recorder2.composeapp.generated.resources.cd_delete_project
import btt_recorder2.composeapp.generated.resources.cd_edit_label
import btt_recorder2.composeapp.generated.resources.info_delete_project_message
import btt_recorder2.composeapp.generated.resources.info_delete_project_title
import btt_recorder2.composeapp.generated.resources.info_dialog_title
import btt_recorder2.composeapp.generated.resources.info_import_none
import btt_recorder2.composeapp.generated.resources.info_import_partial
import btt_recorder2.composeapp.generated.resources.info_import_source_audio_title
import btt_recorder2.composeapp.generated.resources.info_import_success
import btt_recorder2.composeapp.generated.resources.info_importing_source_audio
import btt_recorder2.composeapp.generated.resources.info_row_mode
import btt_recorder2.composeapp.generated.resources.info_row_project
import btt_recorder2.composeapp.generated.resources.info_row_source_audio
import btt_recorder2.composeapp.generated.resources.info_row_source_audio_language
import btt_recorder2.composeapp.generated.resources.info_row_target_language
import btt_recorder2.composeapp.generated.resources.info_row_translation_type
import btt_recorder2.composeapp.generated.resources.info_source_audio_available
import btt_recorder2.composeapp.generated.resources.info_source_audio_imported
import btt_recorder2.composeapp.generated.resources.info_source_audio_not_available
import btt_recorder2.composeapp.generated.resources.info_translation_type_regular
import btt_recorder2.composeapp.generated.resources.info_translation_type_udb
import btt_recorder2.composeapp.generated.resources.info_translation_type_ulb
import btt_recorder2.composeapp.generated.resources.info_value_unknown
import btt_recorder2.composeapp.generated.resources.value_name_with_code
import java.util.Locale

/**
 * Modal info dialog matching the original BTT-Recorder ProjectInfoDialog.
 *
 * Shows project metadata (book, target language, translation type, mode, source-audio info),
 * a delete action, and a pencil affordance on the Source Audio row that triggers the file
 * picker to import per-chapter source audio. The Source Audio Language pencil is preserved
 * for visual fidelity but inert until the language editor screen is ported. Export/share
 * buttons from the original dialog are deliberately omitted for now.
 */
@Composable
fun ProjectInfoDialog(
    workbook: WorkbookDescriptor,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onBackup: () -> Unit = {},
    isExportingThisWorkbook: Boolean = false
) {
    val importer = remember { getKoin().get<SourceAudioImporter>() }
    val scope = rememberCoroutineScope()

    var pendingDelete by remember { mutableStateOf(false) }
    // Recompute on each (re)open so freshly-imported files are reflected. The
    // result is also force-refreshed after a successful import below.
    var hasUserImportedAudio by remember(workbook.id) {
        mutableStateOf(importer.hasUserImportedSourceAudio(workbook))
    }
    var importStatus by remember { mutableStateOf<ImportStatus?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    val picker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = SourceAudioImporter.pickerExtensions),
        mode = FileKitMode.Multiple(),
        title = stringResource(Res.string.info_import_source_audio_title)
    ) { selectedFiles: List<PlatformFile>? ->
        val files = selectedFiles.orEmpty()
        if (files.isEmpty()) return@rememberFilePickerLauncher
        isImporting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                importer.importForWorkbook(workbook, files)
            }
            hasUserImportedAudio = importer.hasUserImportedSourceAudio(workbook)
            importStatus = ImportStatus.from(result)
            isImporting = false
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(stringResource(Res.string.info_delete_project_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.info_delete_project_message,
                        workbook.title,
                        workbook.targetLanguage.anglicizedName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = false
                    onDelete()
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Title — "{Book} - {Language}"
                Text(
                    text = stringResource(
                        Res.string.info_dialog_title,
                        workbook.title,
                        workbook.targetLanguage.anglicizedName
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)
                )

                // Scrollable info area
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    InfoRow(
                        label = stringResource(Res.string.info_row_project),
                        value = stringResource(Res.string.value_name_with_code, workbook.title, workbook.slug)
                    )

                    InfoRow(
                        label = stringResource(Res.string.info_row_target_language),
                        value = stringResource(
                            Res.string.value_name_with_code,
                            workbook.targetLanguage.anglicizedName,
                            workbook.targetLanguage.slug
                        )
                    )

                    InfoRow(
                        label = stringResource(Res.string.info_row_translation_type),
                        value = formatTranslationType(workbook.targetCollection.resourceContainer?.identifier)
                    )

                    InfoRow(
                        label = stringResource(Res.string.info_row_mode),
                        value = workbook.mode.name.lowercase().replaceFirstChar { it.uppercase() }
                    )

                    InfoRowWithEdit(
                        label = stringResource(Res.string.info_row_source_audio_language),
                        value = formatSourceLanguage(workbook),
                        onEdit = { /* TODO: source-audio language editor not yet ported */ },
                        editEnabled = false
                    )

                    InfoRowWithEdit(
                        label = stringResource(Res.string.info_row_source_audio),
                        value = sourceAudioStatus(workbook, hasUserImportedAudio),
                        onEdit = { picker.launch() },
                        editEnabled = !isImporting
                    )

                    importStatus?.let { status ->
                        ImportStatusBanner(
                            status = status,
                            onDismiss = { importStatus = null }
                        )
                    }
                    if (isImporting) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(Res.string.info_importing_source_audio),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )

                // Action row — delete + backup on the left, close on the right.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { pendingDelete = true },
                        enabled = !isExportingThisWorkbook
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(Res.string.cd_delete_project),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = onBackup,
                        enabled = !isExportingThisWorkbook
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudUpload,
                            contentDescription = stringResource(Res.string.cd_back_up_project),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 140.dp).padding(end = 12.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun InfoRowWithEdit(
    label: String,
    value: String,
    onEdit: () -> Unit,
    editEnabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 140.dp).padding(end = 12.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onEdit,
            enabled = editEnabled,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(Res.string.cd_edit_label, label),
                tint = if (editEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun ImportStatusBanner(status: ImportStatus, onDismiss: () -> Unit) {
    val (containerColor, contentColor) = when (status.severity) {
        Severity.SUCCESS -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
        Severity.PARTIAL -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        Severity.ERROR -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        color = containerColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        val headline = when (status.severity) {
            Severity.SUCCESS -> stringResource(Res.string.info_import_success, status.importedCount)
            Severity.PARTIAL -> stringResource(Res.string.info_import_partial, status.importedCount, status.total)
            Severity.ERROR -> stringResource(Res.string.info_import_none)
        }
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
            if (status.detailLines.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                status.detailLines.forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(Res.string.action_dismiss), color = contentColor, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private enum class Severity { SUCCESS, PARTIAL, ERROR }

private data class ImportStatus(
    val severity: Severity,
    val importedCount: Int,
    val total: Int,
    val detailLines: List<String>
) {
    companion object {
        fun from(result: SourceAudioImporter.Result): ImportStatus {
            val anyImported = result.imported.isNotEmpty()
            val anyProblems = result.skipped.isNotEmpty() || result.errors.isNotEmpty()
            val severity = when {
                anyImported && !anyProblems -> Severity.SUCCESS
                anyImported && anyProblems -> Severity.PARTIAL
                else -> Severity.ERROR
            }
            // The localized headline is composed in ImportStatusBanner from the
            // severity + counts; detail lines are file-level diagnostics from the
            // importer (filenames/format hints) and pass through verbatim.
            return ImportStatus(
                severity = severity,
                importedCount = result.imported.size,
                total = result.total,
                detailLines = result.skipped + result.errors
            )
        }
    }
}

@Composable
private fun sourceAudioStatus(
    workbook: WorkbookDescriptor,
    hasUserImported: Boolean
): String {
    return when {
        hasUserImported -> stringResource(Res.string.info_source_audio_imported)
        workbook.hasSourceAudio -> stringResource(Res.string.info_source_audio_available)
        else -> stringResource(Res.string.info_source_audio_not_available)
    }
}

@Composable
private fun formatTranslationType(identifier: String?): String {
    if (identifier == null) return stringResource(Res.string.info_value_unknown)
    return when (identifier.lowercase()) {
        "ulb" -> stringResource(Res.string.info_translation_type_ulb, identifier)
        "udb" -> stringResource(Res.string.info_translation_type_udb, identifier)
        else -> stringResource(Res.string.info_translation_type_regular, identifier.uppercase(Locale.getDefault()))
    }
}

@Composable
private fun formatSourceLanguage(workbook: WorkbookDescriptor): String {
    // Resolve the (throwing) data access outside any composable call, then pick
    // the localized form — Compose disallows composable calls inside try/catch.
    val lang = remember(workbook.id) { runCatching { workbook.sourceLanguage }.getOrNull() }
    return if (lang != null) {
        stringResource(Res.string.value_name_with_code, lang.anglicizedName, lang.slug)
    } else {
        stringResource(Res.string.info_value_unknown)
    }
}
