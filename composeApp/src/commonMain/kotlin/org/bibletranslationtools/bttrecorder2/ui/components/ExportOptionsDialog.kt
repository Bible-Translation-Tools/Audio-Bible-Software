package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import btt_recorder2.composeapp.generated.resources.Res
import btt_recorder2.composeapp.generated.resources.action_all
import btt_recorder2.composeapp.generated.resources.action_cancel
import btt_recorder2.composeapp.generated.resources.action_dismiss
import btt_recorder2.composeapp.generated.resources.action_export
import btt_recorder2.composeapp.generated.resources.action_none
import btt_recorder2.composeapp.generated.resources.export_load_chapters_error_title
import btt_recorder2.composeapp.generated.resources.export_options_title
import btt_recorder2.composeapp.generated.resources.export_type_backup_subtitle
import btt_recorder2.composeapp.generated.resources.export_type_backup_title
import btt_recorder2.composeapp.generated.resources.export_type_source_audio_subtitle
import btt_recorder2.composeapp.generated.resources.export_type_source_audio_title
import btt_recorder2.composeapp.generated.resources.export_type_label
import btt_recorder2.composeapp.generated.resources.export_chapters_count
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportChapter
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportOptionsState
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportType

/**
 * Pre-export options dialog modeled on Orature's `ExportProjectDialog`:
 *
 *   - Export type chosen via card-style radio buttons (Backup / Source Audio).
 *   - Per-chapter checkbox list. A chapter is "selectable" based on its
 *     progress and the chosen type (any progress for Backup; only complete
 *     chapters for Source Audio). Non-selectable rows are dimmed and inert.
 *   - All / None quick toggles.
 *   - Export button is disabled when zero chapters are selected.
 *
 * The dialog itself doesn't trigger the file save picker — it delegates to
 * [onExport], which the caller hooks up to `FileKit.openFileSaver` so the user
 * picks where to save, then to `ExportProjectViewModel.beginExport`.
 */
@Composable
fun ExportOptionsDialog(
    state: ExportOptionsState,
    onDismiss: () -> Unit,
    onSetType: (ExportType) -> Unit,
    onToggleChapter: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onExport: () -> Unit
) {
    when (state) {
        is ExportOptionsState.Closed -> Unit
        is ExportOptionsState.Loading -> LoadingDialog(state, onDismiss)
        is ExportOptionsState.Error -> ErrorDialog(state, onDismiss)
        is ExportOptionsState.Ready -> ReadyDialog(
            state = state,
            onDismiss = onDismiss,
            onSetType = onSetType,
            onToggleChapter = onToggleChapter,
            onSelectAll = onSelectAll,
            onDeselectAll = onDeselectAll,
            onExport = onExport
        )
    }
}

@Composable
private fun LoadingDialog(state: ExportOptionsState.Loading, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = { Text(stringResource(Res.string.export_options_title, state.descriptor.title)) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

@Composable
private fun ErrorDialog(state: ExportOptionsState.Error, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.export_load_chapters_error_title)) },
        text = {
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_dismiss)) } }
    )
}

@Composable
private fun ReadyDialog(
    state: ExportOptionsState.Ready,
    onDismiss: () -> Unit,
    onSetType: (ExportType) -> Unit,
    onToggleChapter: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onExport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        title = {
            Text(
                text = stringResource(Res.string.export_options_title, state.descriptor.title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(Res.string.export_type_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Card-style radio row for the supported export types.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportTypeCard(
                        title = stringResource(Res.string.export_type_backup_title),
                        subtitle = stringResource(Res.string.export_type_backup_subtitle),
                        selected = state.type == ExportType.BACKUP,
                        onClick = { onSetType(ExportType.BACKUP) }
                    )
                    ExportTypeCard(
                        title = stringResource(Res.string.export_type_source_audio_title),
                        subtitle = stringResource(Res.string.export_type_source_audio_subtitle),
                        selected = state.type == ExportType.SOURCE_AUDIO,
                        onClick = { onSetType(ExportType.SOURCE_AUDIO) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.export_chapters_count, state.selectedChapterSorts.size, state.chapters.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onSelectAll) { Text(stringResource(Res.string.action_all)) }
                    TextButton(onClick = onDeselectAll) { Text(stringResource(Res.string.action_none)) }
                }

                // Bound height so the dialog stays a sane size on phones.
                // The list scrolls when the chapter count is large.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    items(state.chapters, key = { it.sort }) { chapter ->
                        ChapterRow(
                            chapter = chapter,
                            selectable = isSelectable(chapter, state.type),
                            checked = chapter.sort in state.selectedChapterSorts,
                            onToggle = { onToggleChapter(chapter.sort) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onExport,
                enabled = state.canExport
            ) { Text(stringResource(Res.string.action_export)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

@Composable
private fun ExportTypeCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = container,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ExportChapter,
    selectable: Boolean,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val rowAlpha = if (selectable) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectable) Modifier.clickable(onClick = onToggle)
                else Modifier
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked && selectable,
            onCheckedChange = { if (selectable) onToggle() },
            enabled = selectable
        )
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
        // Inline progress bar reminds the user how complete the chapter is —
        // mostly useful for distinguishing "ready to export under Source Audio"
        // (full progress) from "only meaningful under Backup" (partial).
        LinearProgressIndicator(
            progress = { chapter.progress },
            modifier = Modifier.width(64.dp).height(4.dp)
        )
    }
}

private fun isSelectable(chapter: ExportChapter, type: ExportType): Boolean = when (type) {
    ExportType.BACKUP -> chapter.progress > 0f
    else -> chapter.progress >= 1f
}
