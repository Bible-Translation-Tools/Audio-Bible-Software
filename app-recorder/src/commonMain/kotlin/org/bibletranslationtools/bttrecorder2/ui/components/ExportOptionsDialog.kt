package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportChapter
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportOptionsState
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportType
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.export_book_action
import org.bibletranslationtools.shared.resources.export_estimated_size
import org.bibletranslationtools.shared.resources.export_load_chapters_error_title
import org.bibletranslationtools.shared.resources.export_mixed_editions_warning
import org.bibletranslationtools.shared.resources.export_options_title
import org.bibletranslationtools.shared.resources.export_type_backup_title
import org.bibletranslationtools.shared.resources.export_type_source_audio_title
import org.bibletranslationtools.shared.resources.pm_sort_chapter
import org.bibletranslationtools.shared.resources.pm_sort_progress
import org.jetbrains.compose.resources.stringResource

/**
 * Pre-export options dialog modeled on Orature's `ExportProjectDialog`:
 *
 *   - Export type chosen in a left-hand radio column (Backup / Source Audio).
 *   - Per-chapter checkbox list. A chapter is "selectable" based on its
 *     progress and the chosen type (any progress for Backup; only complete
 *     chapters for Source Audio). Non-selectable rows are dimmed and inert.
 *   - The list header's tri-state checkbox selects all / none.
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
    DialogFrame(
        title = stringResource(Res.string.export_options_title, state.descriptor.title),
        onClose = onDismiss
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ErrorDialog(state: ExportOptionsState.Error, onDismiss: () -> Unit) {
    DialogFrame(
        title = stringResource(Res.string.export_load_chapters_error_title),
        onClose = onDismiss
    ) {
        DialogMessage(state.message, color = MaterialTheme.colorScheme.error)
    }
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
    val colors = MaterialTheme.colorScheme
    DialogFrame(
        title = stringResource(Res.string.export_options_title, state.descriptor.title),
        onClose = onDismiss,
        maxWidth = 720.dp,
        footer = {
            Text(
                text = state.estimatedSizeBytes?.let {
                    stringResource(Res.string.export_estimated_size, megabytes(it))
                }.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onExport,
                enabled = state.canExport,
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 16.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.export_book_action), style = MaterialTheme.typography.titleLarge)
            }
        }
    ) {
        Row(modifier = Modifier.weight(1f, fill = false).height(336.dp)) {
            Column(modifier = Modifier.width(240.dp).fillMaxHeight()) {
                ExportTypeRow(
                    title = stringResource(Res.string.export_type_backup_title),
                    selected = state.type == ExportType.BACKUP,
                    onClick = { onSetType(ExportType.BACKUP) }
                )
                ExportTypeRow(
                    title = stringResource(Res.string.export_type_source_audio_title),
                    selected = state.type == ExportType.SOURCE_AUDIO,
                    onClick = { onSetType(ExportType.SOURCE_AUDIO) }
                )
                // A published export of a book whose chapters follow different editions (A13's
                // fallback: allowed, with a warning). A backup restores them as they are.
                val mixed = state.heldBackChapters.filter { it in state.selectedChapterSorts }
                if (state.type != ExportType.BACKUP && mixed.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.export_mixed_editions_warning, localizedList(mixed.map { it.toString() })),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            VerticalDivider(color = colors.outlineVariant)
            Column(modifier = Modifier.weight(1f)) {
                val selectable = state.chapters.filter { isSelectable(it, state.type) }
                val headerState = when {
                    state.selectedChapterSorts.isEmpty() -> ToggleableState.Off
                    state.selectedChapterSorts.size == selectable.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                ChapterRowLayout(
                    modifier = Modifier.background(colors.background),
                    checkbox = {
                        TriStateCheckbox(
                            state = headerState,
                            onClick = if (headerState == ToggleableState.On) onDeselectAll else onSelectAll,
                            enabled = selectable.isNotEmpty()
                        )
                    },
                    title = { Text(stringResource(Res.string.pm_sort_chapter), style = MaterialTheme.typography.titleLarge) },
                    progress = { Text(stringResource(Res.string.pm_sort_progress), style = MaterialTheme.typography.titleLarge) }
                )
                LazyColumnWithScrollbar(modifier = Modifier.weight(1f)) {
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
        }
    }
}

@Composable
private fun ExportTypeRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = if (selected) colors.primary else colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) colors.primary else colors.onSurface,
            modifier = Modifier.size(28.dp)
        )
    }
}

/** Checkbox, chapter and progress columns shared by the list header and its rows. */
@Composable
private fun ChapterRowLayout(
    checkbox: @Composable () -> Unit,
    title: @Composable () -> Unit,
    progress: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().height(48.dp).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        checkbox()
        Box(Modifier.weight(1f).padding(start = 8.dp)) { title() }
        Box(Modifier.width(172.dp)) { progress() }
    }
}

@Composable
private fun ChapterRow(
    chapter: ExportChapter,
    selectable: Boolean,
    checked: Boolean,
    onToggle: () -> Unit
) {
    ChapterRowLayout(
        modifier = Modifier.toggleable(
            value = checked && selectable,
            enabled = selectable,
            role = Role.Checkbox,
            onValueChange = { onToggle() }
        ),
        checkbox = { Checkbox(checked = checked && selectable, onCheckedChange = null, enabled = selectable, modifier = Modifier.size(48.dp)) },
        title = {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.titleLarge,
                color = if (selectable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            )
        },
        progress = { ProgressBar(chapter.progress, Modifier.width(172.dp)) }
    )
}

private fun isSelectable(chapter: ExportChapter, type: ExportType): Boolean = when (type) {
    ExportType.BACKUP -> chapter.progress > 0f
    else -> chapter.progress >= 1f
}

/** The megabyte count only, to one decimal; the unit lives in the string so translators can localize it. */
private fun megabytes(bytes: Long): String = "%.1f".format(bytes / (1024.0 * 1024.0))
