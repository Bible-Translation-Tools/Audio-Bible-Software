package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
    onDelete: () -> Unit
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
        type = FileKitType.File(extensions = listOf("wav", "mp3")),
        mode = FileKitMode.Multiple(),
        title = "Import source audio"
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
            title = { Text("Delete project?") },
            text = {
                Text("This will remove ${workbook.title} (${workbook.targetLanguage.anglicizedName}) and all of its takes from this device.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("Cancel") }
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
                    text = "${workbook.title} - ${workbook.targetLanguage.anglicizedName}",
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
                    InfoRow(label = "Project", value = "${workbook.title} (${workbook.slug})")

                    InfoRow(
                        label = "Target Language",
                        value = "${workbook.targetLanguage.anglicizedName} (${workbook.targetLanguage.slug})"
                    )

                    InfoRow(
                        label = "Translation Type",
                        value = formatTranslationType(workbook.targetCollection.resourceContainer?.identifier)
                    )

                    InfoRow(
                        label = "Mode",
                        value = workbook.mode.name.lowercase().replaceFirstChar { it.uppercase() }
                    )

                    InfoRowWithEdit(
                        label = "Source Audio Language",
                        value = formatSourceLanguage(workbook),
                        onEdit = { /* TODO: source-audio language editor not yet ported */ },
                        editEnabled = false
                    )

                    InfoRowWithEdit(
                        label = "Source Audio",
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
                                text = "Importing source audio…",
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

                // Action row — delete on the left, close on the right.
                // Export/share icons from the original layout intentionally omitted.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { pendingDelete = true }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete project",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
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
                contentDescription = "Edit $label",
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
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = status.headline,
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
                Text("Dismiss", color = contentColor, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private enum class Severity { SUCCESS, PARTIAL, ERROR }

private data class ImportStatus(
    val severity: Severity,
    val headline: String,
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
            val headline = when (severity) {
                Severity.SUCCESS -> "Imported ${result.imported.size} source audio file${"s".takeIf { result.imported.size != 1 } ?: ""}."
                Severity.PARTIAL -> "Imported ${result.imported.size} of ${result.total}. Some files were skipped."
                Severity.ERROR -> "No files imported."
            }
            val details = (result.skipped + result.errors)
            return ImportStatus(severity, headline, details)
        }
    }
}

private fun sourceAudioStatus(
    workbook: WorkbookDescriptor,
    hasUserImported: Boolean
): String {
    return when {
        hasUserImported -> "Imported"
        workbook.hasSourceAudio -> "Available"
        else -> "Not available"
    }
}

private fun formatTranslationType(identifier: String?): String {
    if (identifier == null) return "Unknown"
    return when (identifier.lowercase()) {
        "ulb" -> "Unlocked Literal Bible ($identifier)"
        "udb" -> "Unlocked Dynamic Bible ($identifier)"
        else -> "Regular (${identifier.uppercase(Locale.getDefault())})"
    }
}

private fun formatSourceLanguage(workbook: WorkbookDescriptor): String {
    return try {
        val lang = workbook.sourceLanguage
        "${lang.anglicizedName} (${lang.slug})"
    } catch (_: Throwable) {
        "Unknown"
    }
}
