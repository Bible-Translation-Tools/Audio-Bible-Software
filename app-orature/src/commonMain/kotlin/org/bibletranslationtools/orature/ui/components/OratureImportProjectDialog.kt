package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.bibletranslationtools.orature.platform.droppedPlatformFile
import org.bibletranslationtools.orature.platform.importPickerType
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.cancel
import org.bibletranslationtools.orature.resources.choose_file
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.`continue`
import org.bibletranslationtools.orature.resources.dragAndDropDescription
import org.bibletranslationtools.orature.resources.dragToImport
import org.bibletranslationtools.orature.resources.converting_file
import org.bibletranslationtools.orature.resources.importFailed
import org.bibletranslationtools.orature.resources.importProjectSuccessfulMessage
import org.bibletranslationtools.orature.resources.importResource
import org.bibletranslationtools.orature.resources.import_projects
import org.bibletranslationtools.orature.resources.importing_source_audio
import org.bibletranslationtools.orature.resources.importing_source_text
import org.bibletranslationtools.orature.resources.mergingSource
import org.bibletranslationtools.orature.resources.overridingSource
import org.bibletranslationtools.orature.resources.warning
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureImportState
import org.bibletranslationtools.orature.ui.viewmodels.OratureImportViewModel
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The project-import modal (JVM: `ImportProjectDialog`), opened from the home page's import button.
 * Pick an Orature/RC/Burrito/.tstudio file to import a project; shows progress, a merge/overwrite
 * conflict prompt, and success/error. On success the home project list refreshes (via
 * [OratureImportViewModel] → OratureImportEvents). The drop area (JVM dashed
 * `app-drawer__drag-drop-area`) accepts dragged files on desktop; Choose File is the Android path.
 */
@Composable
fun OratureImportProjectDialog(onDismiss: () -> Unit) {
    val importVm = viewModel { OratureImportViewModel() }
    val importState by importVm.importState.collectAsState()

    // The VM is scoped to the host (NavBackStackEntry) and outlives this dialog's show/hide, so clear
    // any prior run's success/error when the dialog (re)opens — a fresh drop area every time.
    LaunchedEffect(Unit) { importVm.reset() }

    // Extension-filtered on desktop, all-files on Android (SAF can't match .orature by MIME).
    val picker = rememberFilePickerLauncher(
        type = importPickerType(),
        mode = FileKitMode.Single
    ) { file -> file?.let(importVm::importFile) }

    val inProgress = importState is OratureImportState.InProgress

    // On finish (success or failure) the result shows as an app-root snackbar (JVM notification), so
    // the dialog just closes — no inline success/error UI.
    LaunchedEffect(importState) {
        if (importState is OratureImportState.Success || importState is OratureImportState.Error) {
            onDismiss()
        }
    }

    // Drag-and-drop into the drop area (JVM AddFilesView onDragOver/onDragDropped). On drop, import
    // the first file; onEntered/onExited drive the drag-over highlight.
    var dragOver by remember { mutableStateOf(false) }
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragOver = false
                val file = droppedPlatformFile(event) ?: return false
                importVm.importFile(file)
                return true
            }
            override fun onEntered(event: DragAndDropEvent) { dragOver = true }
            override fun onExited(event: DragAndDropEvent) { dragOver = false }
            override fun onEnded(event: DragAndDropEvent) { dragOver = false }
        }
    }
    // Progress runs in its OWN dialog (JVM: a separate ProgressDialog, not the drop dialog).
    if (inProgress) {
        val p = importState as OratureImportState.InProgress
        ImportProgressDialog(percent = p.percent, stepKey = p.stepKey)
    }

    if (!inProgress) Dialog(onDismissRequest = { onDismiss() }) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = RoundedCornerShape(12.dp),
            // No tonalElevation — see OratureInfoDrawer for why (avoids the M3 blue-gray tint on white).
            color = MaterialTheme.colorScheme.surface
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
                // JVM app-drawer__drag-drop-area: 2px dashed border (segments 15,15), radius 20,
                // black-30 → primary + light tint while dragging over.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .then(
                            if (dragOver) Modifier.background(OratureColors.PrimaryLight, RoundedCornerShape(20.dp))
                            else Modifier
                        )
                        .dashedBorder(
                            color = if (dragOver) OratureColors.Primary else DropBorderColor,
                            width = 2.dp,
                            radius = 20.dp
                        )
                        .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                        .padding(25.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, tint = OratureColors.Primary, modifier = Modifier.height(44.dp))

                    // Idle drop area only; progress is a separate dialog and the result is a snackbar.
                    Text(stringResource(Res.string.dragToImport), color = OratureColors.NoteText)
                    Button(
                        onClick = { picker.launch() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                    ) { Text(stringResource(Res.string.choose_file)) }
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

    // Failure is reported via the app-root snackbar (see OratureImportViewModel.notify), not a dialog.
}

/**
 * The import progress dialog (JVM: a separate `ProgressDialog`, not the drop dialog): a title, a
 * progress bar, and a row with the current step message + percentage. Not cancelable while importing
 * (JVM allowClose=false), so its dismiss request is a no-op.
 */
@Composable
private fun ImportProgressDialog(percent: Double, stepKey: String?) {
    Dialog(onDismissRequest = { /* import can't be cancelled here (JVM allowCloseProperty=false) */ }) {
        Surface(
            // JVM confirm-dialog/progress-dialog: 720px wide (clamped to fit smaller screens).
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(Res.string.importResource),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LinearProgressIndicator(
                    progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = OratureColors.Primary
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = importStepText(stepKey),
                        fontSize = 14.sp,
                        color = OratureColors.NoteText,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${percent.roundToInt()}%",
                        fontSize = 14.sp,
                        color = OratureColors.NoteText
                    )
                }
            }
        }
    }
}

/** Localized text for the importer's current-step key (JVM: messages[localizeKey]). */
@Composable
private fun importStepText(stepKey: String?): String = when (stepKey) {
    "mergingSource" -> stringResource(Res.string.mergingSource)
    "importing_source_audio" -> stringResource(Res.string.importing_source_audio)
    "importing_source_text" -> stringResource(Res.string.importing_source_text)
    "overridingSource" -> stringResource(Res.string.overridingSource)
    "converting_file" -> stringResource(Res.string.converting_file)
    else -> ""
}

// JVM -wa-black-30 (rgba(26,26,26,0.3)) — the resting dashed-border color of the drop area.
private val DropBorderColor = Color(0x4D1A1A1A)

/** A dashed rounded-rect border (JVM `-fx-border-style: segments(15, 15)`), drawn inset by half the
 *  stroke width so it isn't clipped at the edges. */
private fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp): Modifier = drawBehind {
    val stroke = Stroke(
        width = width.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
    )
    val inset = width.toPx() / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - width.toPx(), size.height - width.toPx()),
        cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
        style = stroke
    )
}
