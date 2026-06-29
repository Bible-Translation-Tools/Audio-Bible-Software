package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import btt_recorder2.composeapp.generated.resources.Res
import btt_recorder2.composeapp.generated.resources.action_cancel
import btt_recorder2.composeapp.generated.resources.action_dismiss
import btt_recorder2.composeapp.generated.resources.action_ok
import btt_recorder2.composeapp.generated.resources.export_backing_up_title
import btt_recorder2.composeapp.generated.resources.export_complete_message
import btt_recorder2.composeapp.generated.resources.export_complete_title
import btt_recorder2.composeapp.generated.resources.export_failed_title
import btt_recorder2.composeapp.generated.resources.export_status_copying_source
import btt_recorder2.composeapp.generated.resources.export_status_exporting_takes
import btt_recorder2.composeapp.generated.resources.export_status_finishing
import btt_recorder2.composeapp.generated.resources.export_status_preparing
import btt_recorder2.composeapp.generated.resources.export_status_working
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportState
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
    AlertDialog(
        onDismissRequest = { /* non-dismissible while exporting */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = { Text(stringResource(Res.string.export_backing_up_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = humanStatus(state.statusKey, state.progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

@Composable
private fun SuccessDialog(state: ExportState.Success, onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        title = { Text(stringResource(Res.string.export_complete_title)) },
        text = {
            Text(
                text = stringResource(
                    Res.string.export_complete_message,
                    state.producedName,
                    state.destinationName
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) { Text(stringResource(Res.string.action_ok)) }
        }
    )
}

@Composable
private fun ErrorDialog(state: ExportState.Error, onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        title = { Text(stringResource(Res.string.export_failed_title)) },
        text = {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) { Text(stringResource(Res.string.action_dismiss)) }
        }
    )
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
