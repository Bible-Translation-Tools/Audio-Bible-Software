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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectGroup
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectImportState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementUiState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
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
import org.bibletranslationtools.shared.resources.cd_more_options
import org.bibletranslationtools.shared.resources.cd_new_project
import org.bibletranslationtools.shared.resources.import_in_progress
import org.bibletranslationtools.shared.resources.import_success
import org.bibletranslationtools.shared.resources.import_title
import org.bibletranslationtools.shared.resources.info_value_unknown
import org.bibletranslationtools.shared.resources.pm_books_count
import org.bibletranslationtools.shared.resources.pm_group_from_source
import org.bibletranslationtools.shared.resources.pm_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ProjectManagementScreen(
    viewModel: ProjectManagementViewModel,
    onNewProjectClick: () -> Unit,
    onTypeClick: (groupId: String, type: String) -> Unit,
    onSettingsClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadWorkbooks()
    }

    ProjectManagementContent(
        uiState = uiState,
        onNewProjectClick = onNewProjectClick,
        onTypeClick = onTypeClick,
        onSettingsClick = onSettingsClick,
        onImportProject = viewModel::importProject
    )

    val importState by viewModel.importState.collectAsState()
    ProjectImportDialog(state = importState, onAcknowledge = viewModel::acknowledgeImport)
}

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

@Composable
fun ProjectManagementContent(
    uiState: ProjectManagementUiState,
    onNewProjectClick: () -> Unit,
    onTypeClick: (groupId: String, type: String) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit = {},
    onImportProject: (PlatformFile) -> Unit = {}
) {
    ProjectManagementScaffold(
        uiState = uiState,
        onNewProjectClick = onNewProjectClick,
        onSettingsClick = onSettingsClick,
        onImportProject = onImportProject
    ) { success ->
        LanguageList(groups = success.groups, onTypeClick = onTypeClick)
    }
}

/**
 * The frame both project screens share: the title bar (with a back arrow when [onBackClick] is
 * given), New Project and the import/settings menu, and the loading and error states around
 * [content].
 */
@Composable
internal fun ProjectManagementScaffold(
    uiState: ProjectManagementUiState,
    onNewProjectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onImportProject: (PlatformFile) -> Unit,
    onBackClick: (() -> Unit)? = null,
    content: @Composable (ProjectManagementUiState.Success) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val importPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("orature", "zip", "tstudio")),
        mode = FileKitMode.Single,
        title = stringResource(Res.string.import_title)
    ) { file: PlatformFile? -> file?.let(onImportProject) }

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
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (uiState) {
                is ProjectManagementUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ProjectManagementUiState.Success -> content(uiState)
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
internal fun typeLabel(type: String): String =
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
        onNewProjectClick = {}
    )
}

@Preview
@Composable
fun ProjectManagementLoadingPreview() {
    ProjectManagementContent(
        uiState = ProjectManagementUiState.Loading,
        onNewProjectClick = {}
    )
}
