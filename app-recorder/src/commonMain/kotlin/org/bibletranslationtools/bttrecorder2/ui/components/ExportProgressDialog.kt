package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportState
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_cancel
import org.bibletranslationtools.shared.resources.action_ok
import org.bibletranslationtools.shared.resources.export_backing_up_title
import org.bibletranslationtools.shared.resources.export_complete_message
import org.bibletranslationtools.shared.resources.export_complete_title
import org.bibletranslationtools.shared.resources.export_failed_title
import org.bibletranslationtools.shared.resources.export_status_copying_source
import org.bibletranslationtools.shared.resources.export_status_exporting_takes
import org.bibletranslationtools.shared.resources.export_status_finishing
import org.bibletranslationtools.shared.resources.export_status_preparing
import org.bibletranslationtools.shared.resources.export_status_working
import org.jetbrains.compose.resources.stringResource

/**
 * Screen-level modal that surfaces the current [ExportState]. Renders nothing
 * when the state is [ExportState.Idle].
 *
 * While [ExportState.InProgress] the dialog is non-dismissible (no back-press or
 * outside-tap dismiss) — the user must explicitly tap Cancel. On Success or
 * Error the dialog is freely dismissible and the OK / Dismiss button drives the
 * viewmodel back to Idle.
 *
 * Lives at the screen level rather than inside `ProjectInfoDialog` so that
 * closing the info dialog mid-export does not also tear down progress UI; the
 * underlying ExportProjectViewModel keeps the work running.
 */
@Composable
fun ExportProgressDialog(
    state: ExportState,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit
) {
    when (state) {
        is ExportState.Idle -> Unit
        is ExportState.InProgress -> InProgressDialog(state, onCancel)
        is ExportState.Success -> SuccessDialog(state, onAcknowledge)
        is ExportState.Error -> ErrorDialog(state, onAcknowledge)
    }
}

@Composable
private fun InProgressDialog(state: ExportState.InProgress, onCancel: () -> Unit) {
    // No close button and no outside/back dismiss while exporting: only Cancel stops it.
    DialogFrame(
        title = stringResource(Res.string.export_backing_up_title),
        onClose = null,
        footer = {
            TextButton(onClick = onCancel) {
                Text(stringResource(Res.string.action_cancel), style = MaterialTheme.typography.titleLarge)
            }
        }
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProgressBar(state.progress, Modifier.fillMaxWidth())
            Text(
                text = humanStatus(state.statusKey, state.progress),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SuccessDialog(state: ExportState.Success, onAcknowledge: () -> Unit) {
    DialogFrame(
        title = stringResource(Res.string.export_complete_title),
        onClose = onAcknowledge,
        footer = {
            Button(
                onClick = onAcknowledge,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text(stringResource(Res.string.action_ok), style = MaterialTheme.typography.titleLarge)
            }
        }
    ) {
        DialogMessage(
            stringResource(Res.string.export_complete_message, state.producedName, state.destinationName)
        )
    }
}

@Composable
private fun ErrorDialog(state: ExportState.Error, onAcknowledge: () -> Unit) {
    DialogFrame(
        title = stringResource(Res.string.export_failed_title),
        onClose = onAcknowledge
    ) {
        DialogMessage(state.message, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Maps Orature's exporter `messageKey` values (defined in
 * [org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.BackupProjectExporter])
 * to localized, human-readable strings. Falls back to a generic message keyed on
 * progress so the dialog always shows something meaningful before the exporter
 * emits its first labeled progress update.
 */
@Composable
private fun humanStatus(messageKey: String?, progress: Float): String {
    return when (messageKey) {
        "exportingTakes" -> stringResource(Res.string.export_status_exporting_takes)
        "copyingSource" -> stringResource(Res.string.export_status_copying_source)
        null -> when {
            progress <= 0f -> stringResource(Res.string.export_status_preparing)
            progress >= 0.99f -> stringResource(Res.string.export_status_finishing)
            else -> stringResource(Res.string.export_status_working)
        }
        else -> stringResource(Res.string.export_status_working)
    }
}
