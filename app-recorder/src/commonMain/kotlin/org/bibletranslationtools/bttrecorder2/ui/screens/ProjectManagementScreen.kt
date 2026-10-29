package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectInfoDialog
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportOptionsState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportProjectViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectGroup
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProgressFilter
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectImportState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementUiState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SortDirection
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SortField
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SortState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.booksByType
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_back
import org.bibletranslationtools.shared.resources.action_collapse
import org.bibletranslationtools.shared.resources.action_dismiss
import org.bibletranslationtools.shared.resources.action_expand
import org.bibletranslationtools.shared.resources.action_import
import org.bibletranslationtools.shared.resources.action_ok
import org.bibletranslationtools.shared.resources.action_settings
import org.bibletranslationtools.shared.resources.cd_clear_search
import org.bibletranslationtools.shared.resources.cd_filter
import org.bibletranslationtools.shared.resources.ic_status_in_progress
import org.bibletranslationtools.shared.resources.ic_status_not_started
import org.bibletranslationtools.shared.resources.pm_filter_completed
import org.bibletranslationtools.shared.resources.pm_filter_in_progress
import org.bibletranslationtools.shared.resources.pm_filter_not_started
import org.bibletranslationtools.shared.resources.cd_info
import org.bibletranslationtools.shared.resources.cd_more_options
import org.bibletranslationtools.shared.resources.cd_new_project
import org.bibletranslationtools.shared.resources.import_in_progress
import org.bibletranslationtools.shared.resources.import_success
import org.bibletranslationtools.shared.resources.import_title
import org.bibletranslationtools.shared.resources.info_value_unknown
import org.bibletranslationtools.shared.resources.label_update_available
import org.bibletranslationtools.shared.resources.pm_books_count
import org.bibletranslationtools.shared.resources.pm_group_from_source
import org.bibletranslationtools.shared.resources.pm_language_pair
import org.bibletranslationtools.shared.resources.pm_search_books
import org.bibletranslationtools.shared.resources.pm_sort_asc
import org.bibletranslationtools.shared.resources.pm_sort_book
import org.bibletranslationtools.shared.resources.pm_sort_desc
import org.bibletranslationtools.shared.resources.pm_sort_progress
import org.bibletranslationtools.shared.resources.pm_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.mp.KoinPlatform.getKoin

@Composable
fun ProjectManagementScreen(
    viewModel: ProjectManagementViewModel,
    onNewProjectClick: () -> Unit,
    onProjectClick: (WorkbookDescriptor) -> Unit,
    selectedGroupId: String? = null,
    selectedType: String? = null,
    onTypeClick: (groupId: String, type: String) -> Unit = { _, _ -> },
    onBackClick: () -> Unit = {},
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
        selectedGroupId = selectedGroupId,
        selectedType = selectedType,
        onTypeClick = onTypeClick,
        onBackClick = onBackClick,
        onSort = viewModel::toggleSort,
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
    selectedGroupId: String? = null,
    selectedType: String? = null,
    onTypeClick: (groupId: String, type: String) -> Unit = { _, _ -> },
    onBackClick: () -> Unit = {},
    onSort: (SortField) -> Unit = {},
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
    val showBooks = selectedGroupId != null && selectedType != null

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
                        if (showBooks) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(backgroundColor)
        ) {
            when (uiState) {
                is ProjectManagementUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ProjectManagementUiState.Success -> {
                    if (showBooks) {
                        val group = uiState.groups.find { it.id == selectedGroupId }
                        val books = group?.booksByType()?.get(selectedType)
                        if (group == null || books == null) {
                            // Its last book was deleted; there is nothing left to list here.
                            LaunchedEffect(Unit) { onBackClick() }
                        } else {
                            BookList(
                                group = group,
                                type = selectedType,
                                books = books,
                                sortState = uiState.sortState,
                                updatesAvailable = uiState.updatesAvailable,
                                progress = uiState.progress,
                                exportingWorkbookId = exportingWorkbookId,
                                onBackClick = onBackClick,
                                onSort = onSort,
                                onProjectClick = onProjectClick,
                                onInfoClick = { infoDialogTarget = it },
                                onUpdateClick = { editionDialogTarget = it }
                            )
                        }
                    } else {
                        LanguageList(groups = uiState.groups, onTypeClick = onTypeClick)
                    }
                }
                is ProjectManagementUiState.Error -> {
                    Text(
                        text = uiState.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

/** A translation type's display name: its identifier upper-cased, e.g. "ULB". */
@Composable
private fun typeLabel(type: String): String =
    if (type.isEmpty()) stringResource(Res.string.info_value_unknown) else type.uppercase()

@Composable
private fun LanguageList(
    groups: List<ProjectGroup>,
    onTypeClick: (groupId: String, type: String) -> Unit
) {
    // Saveable so the expanded cards survive a trip to a book list and back.
    var expandedGroups by rememberSaveable { mutableStateOf(emptySet<String>()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(groups, key = { it.id }) { group ->
            val types = group.booksByType()
            val isExpanded = group.id in expandedGroups
            LanguageCard(
                group = group,
                types = types,
                isExpanded = isExpanded,
                onClick = {
                    expandedGroups = if (isExpanded) expandedGroups - group.id else expandedGroups + group.id
                },
                onTypeClick = { type -> onTypeClick(group.id, type) }
            )
        }
    }
}

@Composable
private fun LanguageCard(
    group: ProjectGroup,
    types: Map<String, List<WorkbookDescriptor>>,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onTypeClick: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(
                width = if (isExpanded) 2.dp else 1.dp,
                color = if (isExpanded) colors.primary else colors.outlineVariant,
                shape = shape
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isExpanded) colors.primaryContainer else colors.surface)
                .clickable(onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.targetLanguage.anglicizedName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(Res.string.pm_group_from_source, group.sourceLanguage.anglicizedName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant
                )
            }
            Text(
                text = pluralStringResource(Res.plurals.pm_books_count, group.books.size, group.books.size),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (isExpanded) Res.string.action_collapse else Res.string.action_expand
                ),
                modifier = Modifier.size(36.dp)
            )
        }
        if (isExpanded) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                types.forEach { (type, books) ->
                    TypeCard(type = type, bookCount = books.size, onClick = { onTypeClick(type) })
                }
            }
        }
    }
}

@Composable
private fun TypeCard(type: String, bookCount: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = typeLabel(type),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = pluralStringResource(Res.plurals.pm_books_count, bookCount, bookCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun BookList(
    group: ProjectGroup,
    type: String,
    books: List<WorkbookDescriptor>,
    sortState: SortState,
    updatesAvailable: Set<Int>,
    progress: Map<Int, Double>,
    exportingWorkbookId: Int?,
    onBackClick: () -> Unit,
    onSort: (SortField) -> Unit,
    onProjectClick: (WorkbookDescriptor) -> Unit,
    onInfoClick: (WorkbookDescriptor) -> Unit,
    onUpdateClick: (WorkbookDescriptor) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var isExpanded by rememberSaveable { mutableStateOf(true) }
    var filter by rememberSaveable { mutableStateOf<ProgressFilter?>(null) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    val shownBooks = books.filter {
        it.title.contains(query.trim(), ignoreCase = true) &&
            filter?.matches(progress[it.id] ?: 0.0) != false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    Res.string.pm_language_pair,
                    group.targetLanguage.anglicizedName,
                    group.sourceLanguage.anglicizedName
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(role = Role.Button, onClick = onBackClick)
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(stringResource(Res.string.pm_search_books), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (query.isEmpty()) null else {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.cd_clear_search))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = colors.surface,
                        focusedContainerColor = colors.surface,
                        unfocusedBorderColor = colors.outlineVariant
                    ),
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 380.dp).fillMaxWidth()
                )
                Box {
                    FilledIconButton(
                        onClick = { filterMenuExpanded = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (filter != null) {
                                colors.primaryContainer
                            } else colors.onSurface.copy(alpha = 0.06f),
                            contentColor = if (filter != null) colors.primary else colors.onSurface
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Rounded.FilterAlt,
                            contentDescription = stringResource(Res.string.cd_filter),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    FilterMenu(
                        expanded = filterMenuExpanded,
                        selected = filter,
                        onSelect = {
                            filter = if (it == filter) null else it
                            filterMenuExpanded = false
                        },
                        onDismiss = { filterMenuExpanded = false }
                    )
                }
            }
        }

        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.outlineVariant, shape)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = typeLabel(type),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (isExpanded) Res.string.action_collapse else Res.string.action_expand
                    ),
                    modifier = Modifier.size(36.dp)
                )
            }
            if (isExpanded) {
                HorizontalDivider(color = colors.outlineVariant)
                BookSortHeader(sortState = sortState, onSort = onSort)
                HorizontalDivider(color = colors.outlineVariant)
                shownBooks.forEach { workbook ->
                    BookRow(
                        workbook = workbook,
                        isExporting = exportingWorkbookId == workbook.id,
                        updateAvailable = workbook.id in updatesAvailable,
                        progress = progress[workbook.id] ?: 0.0,
                        onClick = { onProjectClick(workbook) },
                        onInfoClick = { onInfoClick(workbook) },
                        onUpdateClick = { onUpdateClick(workbook) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookSortHeader(sortState: SortState, onSort: (SortField) -> Unit) {
    val direction = stringResource(
        if (sortState.direction == SortDirection.ASC) Res.string.pm_sort_asc else Res.string.pm_sort_desc
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button) { onSort(SortField.BOOK) }
                    .semantics { stateDescription = direction },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.pm_sort_book),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Default.SwapVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Text(
            text = stringResource(Res.string.pm_sort_progress),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun FilterMenu(
    expanded: Boolean,
    selected: ProgressFilter?,
    onSelect: (ProgressFilter) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(10.dp),
        containerColor = colors.surface,
        border = BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.1f)),
        modifier = Modifier.widthIn(min = 292.dp)
    ) {
        Text(
            text = stringResource(Res.string.cd_filter),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        listOf(
            Triple(
                ProgressFilter.NOT_STARTED,
                painterResource(Res.drawable.ic_status_not_started),
                Res.string.pm_filter_not_started
            ),
            Triple(
                ProgressFilter.IN_PROGRESS,
                painterResource(Res.drawable.ic_status_in_progress),
                Res.string.pm_filter_in_progress
            ),
            Triple(
                ProgressFilter.COMPLETED,
                rememberVectorPainter(Icons.Outlined.Task),
                Res.string.pm_filter_completed
            )
        ).forEach { (option, icon, label) ->
            DropdownMenuItem(
                text = {
                    Text(stringResource(label), style = MaterialTheme.typography.titleLarge)
                },
                leadingIcon = {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
                },
                onClick = { onSelect(option) },
                modifier = Modifier
                    .background(
                        if (option == selected) colors.primaryContainer else Color.Transparent
                    )
            )
        }
    }
}

@Composable
private fun BookRow(
    workbook: WorkbookDescriptor,
    isExporting: Boolean,
    updateAvailable: Boolean,
    progress: Double,
    onClick: () -> Unit,
    onInfoClick: () -> Unit,
    onUpdateClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = workbook.title,
                style = MaterialTheme.typography.titleLarge
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
        Box(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { progress.toFloat().coerceIn(0f, 1f) },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.width(130.dp).height(8.dp)
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            IconButton(onClick = onInfoClick, enabled = !isExporting) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(Res.string.cd_info),
                    modifier = Modifier.size(32.dp)
                )
            }
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
