package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.path
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.addApp
import org.bibletranslationtools.orature.resources.addAppDescription
import org.bibletranslationtools.orature.resources.applicationName
import org.bibletranslationtools.orature.resources.applicationNamePrompt
import org.bibletranslationtools.orature.resources.browse
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.edit
import org.bibletranslationtools.orature.resources.editVerseMarkers
import org.bibletranslationtools.orature.resources.filePath
import org.bibletranslationtools.orature.resources.record
import org.bibletranslationtools.orature.ui.OratureColors
import org.jetbrains.compose.resources.stringResource

/**
 * Register an external editor (JVM: AddPluginDialog): application name, executable path (with a file
 * picker), and whether it can record and/or edit. [onAdd] receives (name, executablePath, canEdit,
 * canRecord). Args aren't collected — the take's file path is appended when the app launches.
 */
@Composable
fun OratureAddPluginDialog(
    onAdd: (name: String, executable: String, canEdit: Boolean, canRecord: Boolean, canMark: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var canEdit by remember { mutableStateOf(true) }
    var canRecord by remember { mutableStateOf(false) }
    var canMark by remember { mutableStateOf(false) }

    // Resolve a macOS .app bundle to its inner binary on browse, so the field shows the runnable
    // path (editable) — matching Orature's completePluginPath on the file-chooser result.
    val picker = rememberFilePickerLauncher(type = FileKitType.File(), mode = FileKitMode.Single) { file ->
        file?.let { path = org.bibletranslationtools.orature.plugins.resolvePluginExecutable(it.path) }
    }
    val valid = name.isNotBlank() && path.isNotBlank() && (canEdit || canRecord || canMark)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(520.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.addApp),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.close), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text(stringResource(Res.string.addAppDescription), fontSize = 14.sp, color = OratureColors.NoteText)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.applicationName)) },
                    placeholder = { Text(stringResource(Res.string.applicationNamePrompt)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text(stringResource(Res.string.filePath)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { picker.launch() }) { Text(stringResource(Res.string.browse)) }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = canRecord, onCheckedChange = { canRecord = it })
                    Text(stringResource(Res.string.record), color = OratureColors.RegularText)
                    Checkbox(checked = canEdit, onCheckedChange = { canEdit = it }, modifier = Modifier.padding(start = 16.dp))
                    Text(stringResource(Res.string.edit), color = OratureColors.RegularText)
                    Checkbox(checked = canMark, onCheckedChange = { canMark = it }, modifier = Modifier.padding(start = 16.dp))
                    Text(stringResource(Res.string.editVerseMarkers), color = OratureColors.RegularText)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = { onAdd(name, path, canEdit, canRecord, canMark); onDismiss() },
                        enabled = valid,
                        colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                    ) { Text(stringResource(Res.string.addApp)) }
                }
            }
        }
    }
}
