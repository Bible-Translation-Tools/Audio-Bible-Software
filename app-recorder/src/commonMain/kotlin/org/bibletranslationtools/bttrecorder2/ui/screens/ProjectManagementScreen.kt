package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectImportDialog
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectManagementScaffold
import org.bibletranslationtools.bttrecorder2.ui.components.typeLabel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectGroup
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementUiState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.booksByType
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_collapse
import org.bibletranslationtools.shared.resources.action_expand
import org.bibletranslationtools.shared.resources.pm_books_count
import org.bibletranslationtools.shared.resources.pm_group_from_source
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
