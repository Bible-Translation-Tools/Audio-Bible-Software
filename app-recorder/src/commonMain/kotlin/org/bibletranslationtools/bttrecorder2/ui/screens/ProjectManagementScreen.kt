package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.launch
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.bttrecorder2.ui.components.EditionChangeDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ExportOptionsDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ExportProgressDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ProgressPieView
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectInfoDialog
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportOptionsState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportProjectViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectGroup
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectImportState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementUiState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_collapse
import org.bibletranslationtools.shared.resources.action_dismiss
import org.bibletranslationtools.shared.resources.action_expand
import org.bibletranslationtools.shared.resources.action_import
import org.bibletranslationtools.shared.resources.action_ok
import org.bibletranslationtools.shared.resources.action_settings
import org.bibletranslationtools.shared.resources.cd_info
import org.bibletranslationtools.shared.resources.cd_more_options
import org.bibletranslationtools.shared.resources.cd_new_project
import org.bibletranslationtools.shared.resources.cd_record
import org.bibletranslationtools.shared.resources.import_in_progress
import org.bibletranslationtools.shared.resources.import_success
import org.bibletranslationtools.shared.resources.import_title
import org.bibletranslationtools.shared.resources.label_update_available
import org.bibletranslationtools.shared.resources.pm_group_books_count
import org.bibletranslationtools.shared.resources.pm_group_from_source
import org.bibletranslationtools.shared.resources.pm_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.mp.KoinPlatform.getKoin

@Composable
fun ProjectManagementScreen(
    viewModel: ProjectManagementViewModel,
    onNewProjectClick: () -> Unit,
    onProjectClick: (WorkbookDescriptor) -> Unit,
    onRecordClick: (WorkbookDescriptor) -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    val exportViewModel = remember { getKoin().get<ExportProjectViewModel>() }
    val exportState by exportViewModel.state.collectAsState()
    val exportOptionsState by exportViewModel.options.collectAsState()
    val exportingWorkbookId by exportViewModel.exportingWorkbookId.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadWorkbooks()
    }

    ProjectManagementContent(
        uiState = uiState,
        onNewProjectClick = onNewProjectClick,
        onProjectClick = onProjectClick,
        onRecordClick = onRecordClick,
        onDeleteWorkbook = viewModel::deleteWorkbook,
        exportingWorkbookId = exportingWorkbookId,
        onBackupRequest = exportViewModel::openOptions,
        onSettingsClick = onSettingsClick,
        onImportProject = viewModel::importProject,
        onEditionChanged = viewModel::loadWorkbooks
    )

    ExportOptionsDialog(
        state = exportOptionsState,
        onDismiss = exportViewModel::closeOptions,
        onSetType = exportViewModel::setExportType,
        onToggleChapter = exportViewModel::toggleChapter,
        onSelectAll = exportViewModel::selectAllChapters,
        onDeselectAll = exportViewModel::deselectAllChapters,
        onExport = {
            val ready = exportOptionsState as? ExportOptionsState.Ready ?: return@ExportOptionsDialog
            scope.launch {
                val destination = FileKit.openFileSaver(
                    suggestedName = exportViewModel.suggestedExportName(ready.descriptor),
                    extension = exportViewModel.fileExtensionForType(ready.type)
                )
                if (destination != null) {
                    exportViewModel.beginExport(destination)
                }
            }
        }
    )

    ExportProgressDialog(
        state = exportState,
        onCancel = exportViewModel::cancel,
        onAcknowledge = exportViewModel::acknowledge
    )

    val importState by viewModel.importState.collectAsState()
    ProjectImportDialog(state = importState, onAcknowledge = viewModel::acknowledgeImport)
}

@Composable
private fun ProjectImportDialog(
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

@Composable
fun ProjectManagementContent(
    uiState: ProjectManagementUiState,
    onNewProjectClick: () -> Unit,
    onProjectClick: (WorkbookDescriptor) -> Unit,
    onRecordClick: (WorkbookDescriptor) -> Unit = {},
    onDeleteWorkbook: (WorkbookDescriptor) -> Unit = {},
    exportingWorkbookId: Int? = null,
    onBackupRequest: (WorkbookDescriptor) -> Unit = {},
    onEditionChanged: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onImportProject: (PlatformFile) -> Unit = {}
) {
    var infoDialogTarget by remember { mutableStateOf<WorkbookDescriptor?>(null) }
    var editionDialogTarget by remember { mutableStateOf<WorkbookDescriptor?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    // Set of group IDs that are currently collapsed.
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }

    val importPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("orature", "zip", "tstudio")),
        mode = FileKitMode.Single,
        title = stringResource(Res.string.import_title)
    ) { file: PlatformFile? -> file?.let(onImportProject) }

    infoDialogTarget?.let { target ->
        ProjectInfoDialog(
            workbook = target,
            onDismiss = { infoDialogTarget = null },
            onDelete = {
                onDeleteWorkbook(target)
                infoDialogTarget = null
            },
            onBackup = {
                onBackupRequest(target)
                infoDialogTarget = null
            },
            isExportingThisWorkbook = exportingWorkbookId == target.id,
            onChangeEdition = {
                infoDialogTarget = null
                editionDialogTarget = target
            }
        )
    }

    editionDialogTarget?.let { target ->
        EditionChangeDialog(
            projectBookId = target.targetCollection.id,
            onDismiss = { editionDialogTarget = null },
            onChanged = onEditionChanged
        )
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Scaffold(
        topBar = {
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
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.action_import)) },
                                    onClick = {
                                        menuExpanded = false
                                        importPicker.launch()
                                    }
                                )
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(backgroundColor)
        ) {
            when (uiState) {
                is ProjectManagementUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is ProjectManagementUiState.Success -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(uiState.groups, key = { it.id }) { group ->
                            val isCollapsed = group.id in collapsedGroups
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Column {
                                    ProjectGroupHeader(
                                        group = group,
                                        isCollapsed = isCollapsed,
                                        onToggle = {
                                            collapsedGroups = if (isCollapsed) {
                                                collapsedGroups - group.id
                                            } else {
                                                collapsedGroups + group.id
                                            }
                                        }
                                    )
                                    if (!isCollapsed) {
                                        group.books.forEachIndexed { index, workbook ->
                                            BookRow(
                                                workbook = workbook,
                                                isExporting = exportingWorkbookId == workbook.id,
                                                updateAvailable = workbook.id in uiState.updatesAvailable,
                                                onClick = { onProjectClick(workbook) },
                                                onInfoClick = { infoDialogTarget = workbook },
                                                onRecordClick = { onRecordClick(workbook) },
                                                onUpdateClick = { editionDialogTarget = workbook }
                                            )
                                            if (index < group.books.lastIndex) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(start = 16.dp),
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is ProjectManagementUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = uiState.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGroupHeader(
    group: ProjectGroup,
    isCollapsed: Boolean,
    onToggle: () -> Unit
) {
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val onBgColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            contentDescription = stringResource(
                if (isCollapsed) Res.string.action_expand else Res.string.action_collapse
            ),
            tint = onBgColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.targetLanguage.anglicizedName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(Res.string.pm_group_from_source, group.sourceLanguage.anglicizedName),
                style = MaterialTheme.typography.bodySmall,
                color = onBgColor
            )
        }
        Text(
            text = stringResource(Res.string.pm_group_books_count, group.books.size),
            style = MaterialTheme.typography.labelSmall,
            color = onBgColor
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun BookRow(
    workbook: WorkbookDescriptor,
    isExporting: Boolean,
    updateAvailable: Boolean,
    onClick: () -> Unit,
    onInfoClick: () -> Unit,
    onRecordClick: () -> Unit,
    onUpdateClick: () -> Unit = {}
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val disabledColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 32.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = workbook.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (updateAvailable) {
                // A newer edition of this book's source is installed.
                Text(
                    text = stringResource(Res.string.label_update_available),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = !isExporting, role = Role.Button, onClick = onUpdateClick)
                        .padding(vertical = 2.dp)
                )
            }
        }
        ProgressPieView(
            progress = 0,
            modifier = Modifier.size(36.dp),
            strokeWidth = 0f,
            strokeColor = Color.Transparent,
            progressColor = primaryColor,
            backgroundColor = disabledColor
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onInfoClick, enabled = !isExporting) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(Res.string.cd_info),
                tint = if (isExporting) MaterialTheme.colorScheme.outlineVariant
                       else Color.Gray
            )
        }
        IconButton(
            onClick = onRecordClick,
            enabled = !isExporting
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = stringResource(Res.string.cd_record),
                tint = if (isExporting) MaterialTheme.colorScheme.outlineVariant
                       else Color.Gray
            )
        }
    }
}

@Preview
@Composable
fun ProjectManagementPreview() {
    val mockGroup = ProjectGroup(
        id = "en_aaa",
        sourceLanguage = MockData.mockWorkbooks.first().sourceLanguage,
        targetLanguage = MockData.mockWorkbooks.first().targetLanguage,
        books = MockData.mockWorkbooks
    )
    ProjectManagementContent(
        uiState = ProjectManagementUiState.Success(listOf(mockGroup)),
        onNewProjectClick = {},
        onProjectClick = {}
    )
}

@Preview
@Composable
fun ProjectManagementLoadingPreview() {
    ProjectManagementContent(
        uiState = ProjectManagementUiState.Loading,
        onNewProjectClick = {},
        onProjectClick = {}
    )
}
