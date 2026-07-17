package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
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
import org.bibletranslationtools.orature.resources.contributorDescription
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
private val FieldShape = RoundedCornerShape(16.dp)
private val FieldHeight = 60.dp

/**
 * The "Modify Contributors" modal (JVM: `ContributorDialog` + `ContributorInfo`): a description, an
 * add-contributor row (pill text field + filled "+" button), a scrollable list of editable
 * contributor rows (each with a trailing delete icon), the export-license notice + CC BY-SA link,
 * and a full-width primary Save button.
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
            // JVM: .contributor-dialog { -fx-pref-width: 720px; }
            modifier = Modifier.width(720.dp),
            shape = RoundedCornerShape(16.dp),
            // No tonalElevation — see OratureInfoDrawer for why (avoids the M3 blue-gray tint on white).
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Header: title + a filled circular close button (JVM: btn--tertiary borderless, but
                // rendered here as a dark filled circle with a white X to match the reference design).
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.modifyContributors),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = OratureColors.RegularText,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(OratureColors.RegularText, CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(Res.string.close),
                            tint = OratureColors.Foreground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (state.isLoading) {
                    Row(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = OratureColors.Primary)
                    }
                } else {
                    Text(
                        text = stringResource(Res.string.contributorDescription),
                        fontSize = 16.sp,
                        color = OratureColors.RegularText
                    )

                    // Add-contributor row: pill text field + filled primary "+" square button.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(Res.string.contributorName)) },
                            shape = FieldShape,
                            keyboardActions = KeyboardActions(onDone = {
                                vm.addContributor(newName); newName = ""
                            }),
                            modifier = Modifier.weight(1f).height(FieldHeight)
                        )
                        Box(
                            modifier = Modifier
                                .size(FieldHeight)
                                .background(OratureColors.Primary, FieldShape)
                                .clickable { vm.addContributor(newName); newName = "" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(Res.string.addContributor),
                                tint = OratureColors.Foreground
                            )
                        }
                    }

                    // Existing contributors (editable rows), a fixed-height scrollable area (JVM:
                    // contributor-dialog .contributor__list pref-height 400px).
                    Column(
                        modifier = Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        state.contributors.forEachIndexed { index, name ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { vm.editContributor(index, it) },
                                    singleLine = true,
                                    shape = FieldShape,
                                    modifier = Modifier.weight(1f).height(FieldHeight)
                                )
                                IconButton(onClick = { vm.removeContributor(index) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.delete), tint = OratureColors.RegularText)
                                }
                            }
                        }
                    }

                    // License notice + CC BY-SA link.
                    Text(stringResource(Res.string.exportLicenseDescription), fontSize = 14.sp, color = OratureColors.RegularText)
                    Text(
                        text = stringResource(Res.string.licenseCCBYSA),
                        fontSize = 14.sp,
                        color = OratureColors.Primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { openUrl(CC_BY_SA_URL) }
                    )

                    Button(
                        onClick = { vm.save(); onDismiss() },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary),
                        modifier = Modifier.fillMaxWidth().height(FieldHeight)
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = OratureColors.Foreground)
                        Text(
                            stringResource(Res.string.save),
                            fontSize = 18.sp,
                            color = OratureColors.Foreground,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
