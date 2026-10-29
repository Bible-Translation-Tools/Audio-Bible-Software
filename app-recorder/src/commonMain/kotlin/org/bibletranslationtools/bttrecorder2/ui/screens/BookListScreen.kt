package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.launch
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.bttrecorder2.ui.components.Breadcrumb
import org.bibletranslationtools.bttrecorder2.ui.components.CollapsibleCard
import org.bibletranslationtools.bttrecorder2.ui.components.EditionChangeDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ExportOptionsDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ExportProgressDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectImportDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectInfoDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectManagementScaffold
import org.bibletranslationtools.bttrecorder2.ui.components.SortHeaderCell
import org.bibletranslationtools.bttrecorder2.ui.components.typeLabel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportOptionsState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportProjectViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProgressFilter
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectGroup
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementUiState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SortField
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SortState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.booksByType
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.cd_clear_search
import org.bibletranslationtools.shared.resources.cd_filter
import org.bibletranslationtools.shared.resources.cd_info
import org.bibletranslationtools.shared.resources.ic_status_in_progress
import org.bibletranslationtools.shared.resources.ic_status_not_started
import org.bibletranslationtools.shared.resources.label_update_available
import org.bibletranslationtools.shared.resources.pm_filter_completed
import org.bibletranslationtools.shared.resources.pm_filter_in_progress
import org.bibletranslationtools.shared.resources.pm_filter_not_started
import org.bibletranslationtools.shared.resources.pm_language_pair
import org.bibletranslationtools.shared.resources.pm_search_books
import org.bibletranslationtools.shared.resources.pm_sort_book
import org.bibletranslationtools.shared.resources.pm_sort_progress
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.mp.KoinPlatform.getKoin

@Composable
fun BookListScreen(
    viewModel: ProjectManagementViewModel,
    groupId: String,
    type: String,
    onBackClick: () -> Unit,
    onNewProjectClick: () -> Unit,
    onProjectOpened: () -> Unit,
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

    BookListContent(
        uiState = uiState,
        groupId = groupId,
        type = type,
        onBackClick = onBackClick,
        onNewProjectClick = onNewProjectClick,
        onProjectClick = { viewModel.openProject(it, onProjectOpened) },
        onSort = viewModel::toggleSort,
        onBooksShown = viewModel::loadProgress,
        onDeleteWorkbook = viewModel::deleteWorkbook,
        exportingWorkbookId = exportingWorkbookId,
        onBackupRequest = exportViewModel::openOptions,
        onEditionChanged = viewModel::loadWorkbooks,
        onSettingsClick = onSettingsClick,
        onImportProject = viewModel::importProject
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
fun BookListContent(
    uiState: ProjectManagementUiState,
    groupId: String,
    type: String,
    onBackClick: () -> Unit,
    onNewProjectClick: () -> Unit,
    onProjectClick: (WorkbookDescriptor) -> Unit,
    onSort: (SortField) -> Unit = {},
    onBooksShown: (ids: Set<Int>) -> Unit = {},
    onDeleteWorkbook: (WorkbookDescriptor) -> Unit = {},
    exportingWorkbookId: Int? = null,
    onBackupRequest: (WorkbookDescriptor) -> Unit = {},
    onEditionChanged: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onImportProject: (PlatformFile) -> Unit = {}
) {
    var infoDialogTarget by remember { mutableStateOf<WorkbookDescriptor?>(null) }
    var editionDialogTarget by remember { mutableStateOf<WorkbookDescriptor?>(null) }

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

    ProjectManagementScaffold(
        uiState = uiState,
        onNewProjectClick = onNewProjectClick,
        onSettingsClick = onSettingsClick,
        onImportProject = onImportProject,
        onBackClick = onBackClick
    ) { success ->
        val group = success.groups.find { it.id == groupId }
        val books = group?.booksByType()?.get(type)
        if (group == null || books == null) {
            // Its last book was deleted; there is nothing left to list here.
            LaunchedEffect(Unit) { onBackClick() }
        } else {
            val ids = books.mapTo(HashSet()) { it.id }
            LaunchedEffect(ids) { onBooksShown(ids) }

            BookList(
                group = group,
                type = type,
                books = books,
                sortState = success.sortState,
                updatesAvailable = success.updatesAvailable,
                progress = success.progress,
                exportingWorkbookId = exportingWorkbookId,
                onBackClick = onBackClick,
                onSort = onSort,
                onProjectClick = onProjectClick,
                onInfoClick = { infoDialogTarget = it },
                onUpdateClick = { editionDialogTarget = it }
            )
        }
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Breadcrumb(
                stringResource(
                    Res.string.pm_language_pair,
                    group.targetLanguage.anglicizedName,
                    group.sourceLanguage.anglicizedName
                ) to onBackClick
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

        CollapsibleCard(
            title = typeLabel(type),
            isExpanded = isExpanded,
            onToggle = { isExpanded = !isExpanded },
            // Shrinks to its rows, but never past the screen: the rows scroll instead.
            modifier = Modifier.weight(1f, fill = false)
        ) {
            HorizontalDivider(color = colors.outlineVariant)
            BookSortHeader(sortState = sortState, onSort = onSort)
            if (shownBooks.isNotEmpty()) HorizontalDivider(color = colors.outlineVariant)

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(shownBooks, key = { it.id }) { workbook ->
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SortHeaderCell(stringResource(Res.string.pm_sort_book), sortState.field == SortField.BOOK, sortState.direction) {
                onSort(SortField.BOOK)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            SortHeaderCell(stringResource(Res.string.pm_sort_progress), sortState.field == SortField.PROGRESS, sortState.direction) {
                onSort(SortField.PROGRESS)
            }
        }
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
fun BookListPreview() {
    val first = MockData.mockWorkbooks.first()
    BookListContent(
        uiState = ProjectManagementUiState.Success(
            listOf(ProjectGroup("g", first.sourceLanguage, first.targetLanguage, MockData.mockWorkbooks))
        ),
        groupId = "g",
        type = first.targetCollection.resourceContainer?.identifier.orEmpty(),
        onBackClick = {},
        onNewProjectClick = {},
        onProjectClick = {}
    )
}
