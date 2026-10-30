package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_back
import org.bibletranslationtools.shared.resources.action_import
import org.bibletranslationtools.shared.resources.action_settings
import org.bibletranslationtools.shared.resources.cd_more_options
import org.bibletranslationtools.shared.resources.cd_new_project
import org.bibletranslationtools.shared.resources.import_title
import org.bibletranslationtools.shared.resources.pm_title
import org.jetbrains.compose.resources.stringResource

/**
 * The project screens' title bar: a back arrow when [onBackClick] is given, New Project, and a menu
 * with Settings, and Import when [onImportProject] is given.
 */
@Composable
internal fun ProjectManagementTopBar(
    onNewProjectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onImportProject: ((PlatformFile) -> Unit)? = null,
    onBackClick: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val importPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("orature", "zip", "tstudio")),
        mode = FileKitMode.Single,
        title = stringResource(Res.string.import_title)
    ) { file: PlatformFile? -> if (file != null) onImportProject?.invoke(file) }

    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBackClick != null) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                }
                Text(
                    text = stringResource(Res.string.pm_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onNewProjectClick,
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.cd_new_project), style = MaterialTheme.typography.titleLarge)
                }
                // Box anchors the dropdown to the menu button instead of the whole row.
                Box {
                    OutlinedIconButton(
                        onClick = { menuExpanded = true },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(Res.string.cd_more_options),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (onImportProject != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.action_import)) },
                                onClick = {
                                    menuExpanded = false
                                    importPicker.launch()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.action_settings)) },
                            onClick = {
                                menuExpanded = false
                                onSettingsClick()
                            }
                        )
                    }
                }
            }
            HorizontalDivider(color = borderColor)
        }
    }
}
