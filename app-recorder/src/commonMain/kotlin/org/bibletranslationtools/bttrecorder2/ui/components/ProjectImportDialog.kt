package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectImportState
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_dismiss
import org.bibletranslationtools.shared.resources.action_ok
import org.bibletranslationtools.shared.resources.import_in_progress
import org.bibletranslationtools.shared.resources.import_success
import org.bibletranslationtools.shared.resources.import_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ProjectImportDialog(
    state: ProjectImportState,
    onAcknowledge: () -> Unit
) {
    when (state) {
        is ProjectImportState.Idle -> Unit
        is ProjectImportState.InProgress -> AlertDialog(
            onDismissRequest = { /* non-dismissible while importing */ },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(Res.string.import_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.import_in_progress))
                }
            },
            confirmButton = {}
        )
        is ProjectImportState.Success -> AlertDialog(
            onDismissRequest = onAcknowledge,
            title = { Text(stringResource(Res.string.import_title)) },
            text = { Text(stringResource(Res.string.import_success)) },
            confirmButton = { TextButton(onClick = onAcknowledge) { Text(stringResource(Res.string.action_ok)) } }
        )
        is ProjectImportState.Error -> AlertDialog(
            onDismissRequest = onAcknowledge,
            title = { Text(stringResource(Res.string.import_title)) },
            text = { Text(state.message, color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton(onClick = onAcknowledge) { Text(stringResource(Res.string.action_dismiss)) } }
        )
    }
}
