package org.bibletranslationtools.bttrecorder2.ui.screens
 
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import btt_recorder2.composeapp.generated.resources.Res
import btt_recorder2.composeapp.generated.resources.cd_book_sort
import btt_recorder2.composeapp.generated.resources.cd_language_sort
import btt_recorder2.composeapp.generated.resources.cd_new_project
import btt_recorder2.composeapp.generated.resources.cd_progress_sort
import btt_recorder2.composeapp.generated.resources.pm_sort_book
import btt_recorder2.composeapp.generated.resources.pm_sort_language
import btt_recorder2.composeapp.generated.resources.pm_sort_progress
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import org.bibletranslationtools.bttrecorder2.ui.components.ExportOptionsDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ExportProgressDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectCard
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectInfoDialog
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportOptionsState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportProjectViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementUiState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.koin.mp.KoinPlatform.getKoin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectManagementScreen(
    viewModel: ProjectManagementViewModel,
    onNewProjectClick: () -> Unit,
    onProjectClick: (WorkbookDescriptor) -> Unit,
    onRecordClick: (WorkbookDescriptor) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // Export VM is resolved as a Koin singleton — process-lifetime — so it
    // outlives both this screen's recomposition and any other route's
    // ViewModelStore (the Recorder route reads the same singleton to block
    // entry on an actively-exporting workbook). Surviving Android config
    // changes is automatic because the Koin singleton outlives the Activity
    // rebuild.
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
        onBackupRequest = exportViewModel::openOptions
    )

    ExportOptionsDialog(
        state = exportOptionsState,
        onDismiss = exportViewModel::closeOptions,
        onSetType = exportViewModel::setExportType,
        onToggleChapter = exportViewModel::toggleChapter,
        onSelectAll = exportViewModel::selectAllChapters,
        onDeselectAll = exportViewModel::deselectAllChapters,
        onExport = {
            // The options dialog has all the user input we need. Now prompt
            // for a save destination — this is what triggers Android SAF /
            // the native Desktop save dialog. Once the user picks (or
            // cancels), we hand off to the VM's beginExport.
            val ready = exportOptionsState as? ExportOptionsState.Ready ?: return@ExportOptionsDialog
            scope.launch {
                val destination = FileKit.openFileSaver(
                    suggestedName = exportViewModel.suggestedExportName(ready.descriptor),
                    extension = exportViewModel.fileExtensionForType(ready.type)
                )
                if (destination != null) {
                    exportViewModel.beginExport(destination)
                }
                // If the user cancelled the saver, leave the options dialog
                // open so they can adjust selections or try again.
            }
        }
    )

    ExportProgressDialog(
        state = exportState,
        onCancel = exportViewModel::cancel,
        onAcknowledge = exportViewModel::acknowledge
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectManagementContent(
    uiState: ProjectManagementUiState,
    onNewProjectClick: () -> Unit,
    onProjectClick: (WorkbookDescriptor) -> Unit,
    onRecordClick: (WorkbookDescriptor) -> Unit = {},
    onDeleteWorkbook: (WorkbookDescriptor) -> Unit = {},
    exportingWorkbookId: Int? = null,
    onBackupRequest: (WorkbookDescriptor) -> Unit = {}
) {
    // Currently-displayed info dialog target. Null = no dialog.
    var infoDialogTarget by remember { mutableStateOf<WorkbookDescriptor?>(null) }

    infoDialogTarget?.let { target ->
        ProjectInfoDialog(
            workbook = target,
            onDismiss = { infoDialogTarget = null },
            onDelete = {
                onDeleteWorkbook(target)
                infoDialogTarget = null
            },
            onBackup = {
                // Hand off to the screen-level export VM, which opens the
                // ExportOptionsDialog. Close the info dialog so it doesn't
                // overlap the options UI.
                onBackupRequest(target)
                infoDialogTarget = null
            },
            isExportingThisWorkbook = exportingWorkbookId == target.id
        )
    }

    //val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.primary // Example color
    val backgroundColor = MaterialTheme.colorScheme.background // Example color
    val fabColor = MaterialTheme.colorScheme.secondary // Example color
    val textColor = MaterialTheme.colorScheme.onPrimary // Example color
    val buttonColor = MaterialTheme.colorScheme.primary
    val buttonTextColor = MaterialTheme.colorScheme.onPrimary

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Project Management",
                        color = textColor
                    )
                }, // Replace with your title
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = toolbarColor
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewProjectClick,
                containerColor = fabColor,
                contentColor = Color.White
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = stringResource(Res.string.cd_new_project))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(backgroundColor)
        ) {

            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {

                // Language Sort
                Row(
                    modifier = Modifier.weight(0.4f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Icon(
                        imageVector = Icons.Filled.RecordVoiceOver,
                        contentDescription = stringResource(Res.string.cd_language_sort),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(stringResource(Res.string.pm_sort_language))
                }

                // Book Sort
                Row(
                    modifier = Modifier.weight(0.3f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                        contentDescription = stringResource(Res.string.cd_book_sort),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(stringResource(Res.string.pm_sort_book))
                }

                Row(
                    modifier = Modifier.weight(0.3f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Icon(
                        imageVector = Icons.Filled.Book,
                        contentDescription = stringResource(Res.string.cd_progress_sort),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(stringResource(Res.string.pm_sort_progress))
                }
            }

            when (val state = uiState) {
                is ProjectManagementUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is ProjectManagementUiState.Success -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.projects) { workbook ->
                            ProjectCard(
                                workbook = workbook,
                                onWorkbookClick = { onProjectClick(workbook) },
                                onInfoClick = { infoDialogTarget = workbook },
                                onRecordClick = { onRecordClick(workbook) }
                            )
                        }
                    }
                }
                is ProjectManagementUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
@Preview
@Composable
fun ProjectManagementPreview() {
    ProjectManagementContent(
        uiState = ProjectManagementUiState.Success(MockData.mockWorkbooks),
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
