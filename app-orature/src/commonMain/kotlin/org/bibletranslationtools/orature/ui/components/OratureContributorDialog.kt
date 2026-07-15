package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.orature.platform.openUrl
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.addContributor
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.contributorName
import org.bibletranslationtools.orature.resources.delete
import org.bibletranslationtools.orature.resources.exportLicenseDescription
import org.bibletranslationtools.orature.resources.licenseCCBYSA
import org.bibletranslationtools.orature.resources.modifyContributors
import org.bibletranslationtools.orature.resources.save
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureContributorViewModel
import org.jetbrains.compose.resources.stringResource

private const val CC_BY_SA_URL = "https://creativecommons.org/licenses/by-sa/4.0/"

/**
 * The "Modify Contributors" modal (JVM: `ContributorDialog`): list the project's contributors, add /
 * edit / remove them, and Save (persists to the project manifest). Includes the export-license notice
 * and a CC BY-SA link.
 */
@Composable
fun OratureContributorDialog(
    workbookDescriptorId: Int,
    onDismiss: () -> Unit
) {
    val vm = viewModel(key = "contributors-$workbookDescriptorId") { OratureContributorViewModel(workbookDescriptorId) }
    val state by vm.uiState.collectAsState()
    var newName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Header
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.modifyContributors),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.close), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                if (state.isLoading) {
                    Row(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = OratureColors.Primary)
                    }
                } else {
                    // Existing contributors (editable rows).
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.contributors.forEachIndexed { index, name ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { vm.editContributor(index, it) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { vm.removeContributor(index) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.delete), tint = OratureColors.NoteText)
                                }
                            }
                        }
                    }

                    // Add-contributor row.
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(Res.string.contributorName)) },
                            keyboardActions = KeyboardActions(onDone = {
                                vm.addContributor(newName); newName = ""
                            }),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { vm.addContributor(newName); newName = "" }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.addContributor), tint = OratureColors.Primary)
                        }
                    }

                    // License notice + CC BY-SA link.
                    Text(stringResource(Res.string.exportLicenseDescription), fontSize = 13.sp, color = OratureColors.NoteText)
                    Text(
                        text = stringResource(Res.string.licenseCCBYSA),
                        fontSize = 13.sp,
                        color = OratureColors.Primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { openUrl(CC_BY_SA_URL) }
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { vm.save(); onDismiss() },
                            colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                        ) { Text(stringResource(Res.string.save)) }
                    }
                }
            }
        }
    }
}
