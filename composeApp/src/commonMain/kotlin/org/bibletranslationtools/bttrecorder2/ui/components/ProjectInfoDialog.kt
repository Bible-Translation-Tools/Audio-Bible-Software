package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.background
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
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import java.util.Locale

/**
 * Modal info dialog matching the original BTT-Recorder ProjectInfoDialog.
 *
 * Shows project metadata (book, target language, translation type, mode, source-audio info),
 * a delete action, and pencil affordances on source-audio rows. The pencils are wired but
 * intentionally inert until the source-audio editor screen is ported. Export/share buttons
 * from the original dialog are deliberately omitted for now.
 */
@Composable
fun ProjectInfoDialog(
    workbook: WorkbookDescriptor,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onEditSourceLanguage: () -> Unit = {},
    onEditSourceLocation: () -> Unit = {}
) {
    var pendingDelete by remember { mutableStateOf(false) }

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
                .heightIn(max = 560.dp),
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
                        onEdit = onEditSourceLanguage
                    )

                    InfoRowWithEdit(
                        label = "Source Audio",
                        value = if (workbook.hasSourceAudio) "Available" else "Not available",
                        onEdit = onEditSourceLocation
                    )
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
private fun InfoRowWithEdit(label: String, value: String, onEdit: () -> Unit) {
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
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
