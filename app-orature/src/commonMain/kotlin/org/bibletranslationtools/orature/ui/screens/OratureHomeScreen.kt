package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.components.OratureBookTable
import org.bibletranslationtools.orature.ui.components.OratureImportButton
import org.bibletranslationtools.orature.ui.components.OratureProjectWizardSection
import org.bibletranslationtools.orature.ui.components.OratureNewProjectCard
import org.bibletranslationtools.orature.ui.components.OratureProjectGroupCard
import org.bibletranslationtools.orature.ui.components.projectModeLabel
import org.bibletranslationtools.orature.ui.viewmodels.OratureBookUiModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureHomeUiState
import org.bibletranslationtools.orature.ui.viewmodels.OratureHomeViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureProjectGroupKey
import org.bibletranslationtools.orature.ui.viewmodels.OratureProjectGroupUiModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureProjectWizardViewModel
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.createProjectMessageBody
import org.bibletranslationtools.orature.resources.createProjectMessageTitle
import org.bibletranslationtools.orature.resources.exportFailed
import org.bibletranslationtools.orature.resources.exportSuccessful
import org.bibletranslationtools.orature.resources.modifyContributors
import org.bibletranslationtools.orature.resources.options
import org.bibletranslationtools.orature.resources.showLocation
import kotlinx.coroutines.launch
import org.bibletranslationtools.orature.resources.projectGroupTitle
import org.bibletranslationtools.orature.resources.projects
import org.bibletranslationtools.orature.resources.search

/**
 * Orature's real home: a persistent nav rail, a 320dp Projects pane of project-group
 * cards on the left, and the BookSection (header + book table) filling the rest — matching
 * HomePage2's borderpane(left = projects, center = BookSection).
 */
@Composable
fun OratureHomeScreen(
    viewModel: OratureHomeViewModel,
    wizardViewModel: OratureProjectWizardViewModel,
    onBookClick: (OratureBookUiModel) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val wizardState by wizardViewModel.uiState.collectAsState()

    // Center-pane mode: BOOK_TABLE by default; the new-project card swaps in the WIZARD.
    // On cancel/complete we swap back and the wizard VM's onComplete reloads projects.
    var centerMode by remember { mutableStateOf(CenterPaneMode.BOOK_TABLE) }
    // The project-import modal (JVM: ImportProjectDialog), opened from the home import button.
    var showImport by remember { mutableStateOf(false) }

    // When the wizard finishes creating (or matching) a project, close it, return to the
    // book table, and reselect the created group — mirrors Orature's onNavigateBack
    // (mainSection → bookFragment) + bookMarkedProjectGroupProperty selection.
    LaunchedEffect(wizardViewModel) {
        wizardViewModel.projectCreated.collect { created ->
            centerMode = CenterPaneMode.BOOK_TABLE
            viewModel.selectCreatedProject(created)
        }
    }

    OratureHomeContent(
        uiState = uiState,
        wizardState = wizardState,
        centerMode = centerMode,
        onSelectGroup = { key ->
            // Selecting a project group returns to the book table (JVM: exitWizard on group tap).
            if (centerMode == CenterPaneMode.WIZARD) {
                wizardViewModel.reset()
                centerMode = CenterPaneMode.BOOK_TABLE
            }
            viewModel.onSelectProjectGroup(key)
        },
        onBookSearchQueryChange = viewModel::onBookSearchQueryChange,
        onBookClick = { book ->
            viewModel.onBookClick(book)
            onBookClick(book)
        },
        onNewProjectClick = {
            viewModel.onNewProjectClick()
            wizardViewModel.reset()
            centerMode = CenterPaneMode.WIZARD
        },
        onImportClick = { showImport = true },
        onWizardModeSelected = wizardViewModel::onModeSelected,
        onWizardBack = {
            // Step-1 back cancels the wizard and returns to the book table.
            if (!wizardViewModel.onBack()) {
                wizardViewModel.reset()
                centerMode = CenterPaneMode.BOOK_TABLE
            }
        },
        onWizardLanguageSelected = wizardViewModel::onLanguageSelected,
        onWizardResourceVersionSelected = wizardViewModel::onResourceVersionSelected,
        onWizardSourceSearchChange = wizardViewModel::onSourceLanguageSearchQueryChange,
        onWizardTargetSearchChange = wizardViewModel::onTargetLanguageSearchQueryChange
    )

    if (showImport) {
        org.bibletranslationtools.orature.ui.components.OratureImportProjectDialog(
            onDismiss = { showImport = false }
        )
    }
}

/** What occupies the home center region: the book table, or the project-creation wizard. */
enum class CenterPaneMode { BOOK_TABLE, WIZARD }

@Composable
fun OratureHomeContent(
    uiState: OratureHomeUiState,
    wizardState: org.bibletranslationtools.orature.ui.viewmodels.WizardUiState,
    centerMode: CenterPaneMode,
    onSelectGroup: (OratureProjectGroupKey) -> Unit,
    onBookSearchQueryChange: (String) -> Unit,
    onBookClick: (OratureBookUiModel) -> Unit,
    onNewProjectClick: () -> Unit,
    onImportClick: () -> Unit,
    onWizardModeSelected: (org.bibletranslationtools.otter.common.data.primitives.ProjectMode) -> Unit,
    onWizardBack: () -> Unit,
    onWizardLanguageSelected: (org.bibletranslationtools.otter.common.data.primitives.Language) -> Unit,
    onWizardResourceVersionSelected: (org.bibletranslationtools.orature.ui.viewmodels.OratureResourceVersion) -> Unit,
    onWizardSourceSearchChange: (String) -> Unit,
    onWizardTargetSearchChange: (String) -> Unit
) {
    // The nav rail + Settings/Info drawers now live in the persistent OratureRootShell
    // (present on every screen); the home content is just the projects pane + center section.
    Row(modifier = Modifier.fillMaxSize()) {
        OratureProjectsPane(
            uiState = uiState,
            // JVM: the new-project card is hidden while the wizard is docked.
            showNewProjectCard = centerMode == CenterPaneMode.BOOK_TABLE,
            onSelectGroup = onSelectGroup,
            onNewProjectClick = onNewProjectClick,
            onImportClick = onImportClick,
            modifier = Modifier.width(320.dp).fillMaxHeight()
        )

        // Center region swaps between the book table and the wizard (JVM: mainSectionProperty).
        when (centerMode) {
            CenterPaneMode.BOOK_TABLE -> OratureBookSection(
                uiState = uiState,
                onBookSearchQueryChange = onBookSearchQueryChange,
                onBookClick = onBookClick,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            CenterPaneMode.WIZARD -> OratureProjectWizardSection(
                state = wizardState,
                onModeSelected = onWizardModeSelected,
                onBack = onWizardBack,
                onLanguageSelected = onWizardLanguageSelected,
                onResourceVersionSelected = onWizardResourceVersionSelected,
                onSourceSearchQueryChange = onWizardSourceSearchChange,
                onTargetSearchQueryChange = onWizardTargetSearchChange,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun OratureProjectsPane(
    uiState: OratureHomeUiState,
    showNewProjectCard: Boolean,
    onSelectGroup: (OratureProjectGroupKey) -> Unit,
    onNewProjectClick: () -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp)
    ) {
        Text(
            text = stringResource(Res.string.projects),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (showNewProjectCard) {
            OratureNewProjectCard(onClick = onNewProjectClick)
            Spacer(modifier = Modifier.height(12.dp))
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.isEmptyGroups -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(Res.string.createProjectMessageTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.projectGroups, key = { it.key }) { group ->
                        OratureProjectGroupCard(
                            group = group,
                            isSelected = group.key == uiState.selectedGroupKey,
                            onClick = { onSelectGroup(group.key) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OratureImportButton(onClick = onImportClick)
    }
}

@Composable
private fun OratureBookSection(
    uiState: OratureHomeUiState,
    onBookSearchQueryChange: (String) -> Unit,
    onBookClick: (OratureBookUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val selectedGroup: OratureProjectGroupUiModel? = uiState.selectedGroup
    // The book whose Export dialog is open (JVM: WorkbookExportDialogOpenEvent), or null.
    var exportBookId by remember { mutableStateOf<Int?>(null) }
    // The group (its first book's descriptor id) whose Contributors dialog is open, or null.
    var contributorsForId by remember { mutableStateOf<Int?>(null) }
    // Export-finish toast (JVM: WorkbookExportFinishEvent → snackbar notification).
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val exportSuccessMsg = stringResource(Res.string.exportSuccessful)
    val exportFailMsg = stringResource(Res.string.exportFailed)
    val showLocationLabel = stringResource(Res.string.showLocation)
    val canShowLocation = org.bibletranslationtools.orature.platform.canOpenInFileManager()

    Box(modifier = modifier) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OratureColors.Background)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(Res.string.options)
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    // JVM: ProjectGroupOptionMenu — Modify Contributors (Delete Project is deferred
                    // with the project-deletion-queue guard).
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.modifyContributors)) },
                        enabled = selectedGroup?.books?.isNotEmpty() == true,
                        onClick = {
                            menuExpanded = false
                            contributorsForId = selectedGroup?.books?.firstOrNull()?.id
                        }
                    )
                }
            }

            val title = if (selectedGroup != null) {
                stringResource(
                    Res.string.projectGroupTitle,
                    selectedGroup.targetLanguageName,
                    projectModeLabel(selectedGroup.mode)
                )
            } else {
                ""
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )

            OutlinedTextField(
                value = uiState.bookSearchQuery,
                onValueChange = onBookSearchQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(Res.string.search)) },
                modifier = Modifier.width(240.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = uiState.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            selectedGroup == null || uiState.visibleBooks.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(Res.string.createProjectMessageBody),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                OratureBookTable(
                    books = uiState.visibleBooks,
                    onBookClick = onBookClick,
                    onExportBook = { book -> exportBookId = book.id },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    contributorsForId?.let { id ->
        org.bibletranslationtools.orature.ui.components.OratureContributorDialog(
            workbookDescriptorId = id,
            onDismiss = { contributorsForId = null }
        )
    }

    exportBookId?.let { id ->
        org.bibletranslationtools.orature.ui.components.OratureExportProjectDialog(
            workbookDescriptorId = id,
            onDismiss = { exportBookId = null },
            onFinished = { success, location ->
                exportBookId = null
                scope.launch {
                    val withLocation = success && canShowLocation && location != null
                    val result = snackbarHostState.showSnackbar(
                        message = if (success) exportSuccessMsg else exportFailMsg,
                        actionLabel = if (withLocation) showLocationLabel else null
                    )
                    if (withLocation && result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        org.bibletranslationtools.orature.platform.openInFileManager(location!!)
                    }
                }
            }
        )
    }

        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}
