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
import org.bibletranslationtools.orature.ui.components.OratureNavDestination
import org.bibletranslationtools.orature.ui.components.OratureNavRail
import org.bibletranslationtools.orature.ui.components.OratureNewProjectCard
import org.bibletranslationtools.orature.ui.components.OratureProjectGroupCard
import org.bibletranslationtools.orature.ui.components.projectModeLabel
import org.bibletranslationtools.orature.ui.viewmodels.OratureBookUiModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureHomeUiState
import org.bibletranslationtools.orature.ui.viewmodels.OratureHomeViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureProjectGroupKey
import org.bibletranslationtools.orature.ui.viewmodels.OratureProjectGroupUiModel
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.orature_cd_more_options
import org.bibletranslationtools.shared.resources.orature_empty_books
import org.bibletranslationtools.shared.resources.orature_empty_groups
import org.bibletranslationtools.shared.resources.orature_project_group_title
import org.bibletranslationtools.shared.resources.orature_projects_title
import org.bibletranslationtools.shared.resources.orature_search

/**
 * Orature's real home: a persistent nav rail, a 320dp Projects pane of project-group
 * cards on the left, and the BookSection (header + book table) filling the rest — matching
 * HomePage2's borderpane(left = projects, center = BookSection).
 */
@Composable
fun OratureHomeScreen(
    viewModel: OratureHomeViewModel,
    onBookClick: (OratureBookUiModel) -> Unit,
    onNewProjectClick: () -> Unit,
    onImportClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    OratureHomeContent(
        uiState = uiState,
        onSelectGroup = viewModel::onSelectProjectGroup,
        onBookSearchQueryChange = viewModel::onBookSearchQueryChange,
        onBookClick = { book ->
            viewModel.onBookClick(book)
            onBookClick(book)
        },
        onNewProjectClick = {
            viewModel.onNewProjectClick()
            onNewProjectClick()
        },
        onImportClick = {
            viewModel.onImportClick()
            onImportClick()
        },
        onSettingsClick = onSettingsClick,
        onInfoClick = onInfoClick
    )
}

@Composable
fun OratureHomeContent(
    uiState: OratureHomeUiState,
    onSelectGroup: (OratureProjectGroupKey) -> Unit,
    onBookSearchQueryChange: (String) -> Unit,
    onBookClick: (OratureBookUiModel) -> Unit,
    onNewProjectClick: () -> Unit,
    onImportClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        OratureNavRail(
            selected = OratureNavDestination.HOME,
            onHomeClick = { /* no-op: already home */ },
            onSettingsClick = onSettingsClick,
            onInfoClick = onInfoClick
        )

        OratureProjectsPane(
            uiState = uiState,
            onSelectGroup = onSelectGroup,
            onNewProjectClick = onNewProjectClick,
            onImportClick = onImportClick,
            modifier = Modifier.width(320.dp).fillMaxHeight()
        )

        OratureBookSection(
            uiState = uiState,
            onBookSearchQueryChange = onBookSearchQueryChange,
            onBookClick = onBookClick,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

@Composable
private fun OratureProjectsPane(
    uiState: OratureHomeUiState,
    onSelectGroup: (OratureProjectGroupKey) -> Unit,
    onNewProjectClick: () -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp)
    ) {
        Text(
            text = stringResource(Res.string.orature_projects_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        OratureNewProjectCard(onClick = onNewProjectClick)

        Spacer(modifier = Modifier.height(12.dp))

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.isEmptyGroups -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(Res.string.orature_empty_groups),
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

    Column(
        modifier = modifier
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
                        contentDescription = stringResource(Res.string.orature_cd_more_options)
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    // Stub menu — populated with real project-group actions in a later phase.
                    DropdownMenuItem(text = { Text(stringResource(Res.string.orature_cd_more_options)) }, onClick = { menuExpanded = false })
                }
            }

            val title = if (selectedGroup != null) {
                stringResource(
                    Res.string.orature_project_group_title,
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
                placeholder = { Text(stringResource(Res.string.orature_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(Res.string.orature_search)) },
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
                        text = stringResource(Res.string.orature_empty_books),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                OratureBookTable(
                    books = uiState.visibleBooks,
                    onBookClick = onBookClick,
                    onRowOptionsClick = { /* stub — per-row overflow menu, later phase */ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
