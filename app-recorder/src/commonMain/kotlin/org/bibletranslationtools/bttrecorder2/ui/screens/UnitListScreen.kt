package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.bttrecorder2.ui.components.Breadcrumb
import org.bibletranslationtools.bttrecorder2.ui.components.CollapsibleCard
import org.bibletranslationtools.bttrecorder2.ui.components.LazyColumnWithScrollbar
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectManagementTopBar
import org.bibletranslationtools.bttrecorder2.ui.components.SearchFilterBar
import org.bibletranslationtools.bttrecorder2.ui.components.SortHeaderCell
import org.bibletranslationtools.bttrecorder2.ui.components.TakePlayer
import org.bibletranslationtools.bttrecorder2.ui.components.typeLabel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SortDirection
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListUiState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListViewModel
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_collapse
import org.bibletranslationtools.shared.resources.action_expand
import org.bibletranslationtools.shared.resources.action_no
import org.bibletranslationtools.shared.resources.action_yes
import org.bibletranslationtools.shared.resources.cd_delete_take
import org.bibletranslationtools.shared.resources.cd_record
import org.bibletranslationtools.shared.resources.error_prefix
import org.bibletranslationtools.shared.resources.pm_column_takes
import org.bibletranslationtools.shared.resources.pm_language_pair
import org.bibletranslationtools.shared.resources.pm_search_verses
import org.bibletranslationtools.shared.resources.pm_sort_verses
import org.bibletranslationtools.shared.resources.unit_chapter_title
import org.bibletranslationtools.shared.resources.unit_delete_take_title
import org.bibletranslationtools.shared.resources.unit_take_count
import org.bibletranslationtools.shared.resources.unit_verse_label
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview


@Composable
fun UnitListScreen(
    viewModel: UnitListViewModel = viewModel { UnitListViewModel() },
    onBackClick: () -> Unit,
    onLanguagesClick: () -> Unit,
    onBooksClick: () -> Unit,
    onNewProjectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onUnitClick: (sourceId: Int, targetId: Int, chapterSort: Int, unitSort: Int) -> Unit,
    onOpenPlayback: (sourceId: Int, targetId: Int, chapterSort: Int, unitSort: Int, takeNumber: Int) -> Unit
) {
    LaunchedEffect(Unit) {
        viewModel.loadUnits()
    }

    val uiState by viewModel.uiState.collectAsState()
    val workbook = uiState.workbook
    val chapter = uiState.chapter

    UnitListContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onLanguagesClick = onLanguagesClick,
        onBooksClick = onBooksClick,
        onNewProjectClick = onNewProjectClick,
        onSettingsClick = onSettingsClick,
        onUnitClick = { unitSort ->
            if (workbook != null && chapter != null) viewModel.selectUnit(unitSort) {
                onUnitClick(workbook.source.collectionId, workbook.target.collectionId, chapter.sort, unitSort)
            }
        },
        onPlayPause = { viewModel.togglePlay(it) },
        onDelete = { unit, take -> viewModel.deleteTake(unit, take) },
        onCycle = { unit, direction -> viewModel.cycleTake(unit, direction) },
        onSelectTake = { viewModel.selectCurrentTake(it) },
        onOpenPlayback = { unitSort, takeNumber ->
            if (workbook != null && chapter != null) {
                onOpenPlayback(workbook.source.collectionId, workbook.target.collectionId, chapter.sort, unitSort, takeNumber)
            }
        }
    )
}

@Composable
fun UnitListContent(
    uiState: UnitListUiState,
    onBackClick: () -> Unit,
    onUnitClick: (Int) -> Unit,
    onPlayPause: (Chunk) -> Unit,
    onDelete: (Chunk, Take) -> Unit,
    onCycle: (Chunk, Int) -> Unit,
    onSelectTake: (Chunk) -> Unit,
    onOpenPlayback: (unitSort: Int, takeNumber: Int) -> Unit,
    onLanguagesClick: () -> Unit = {},
    onBooksClick: () -> Unit = {},
    onNewProjectClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    var expandedUnitSort by remember { mutableStateOf<Int?>(null) }
    var isCardExpanded by rememberSaveable { mutableStateOf(true) }
    var sortDirection by rememberSaveable { mutableStateOf(SortDirection.ASC) }
    var query by rememberSaveable { mutableStateOf("") }
    val units = remember(uiState.units, sortDirection) {
        uiState.units.sortedBy { it.unit.sort }.let { if (sortDirection == SortDirection.DESC) it.reversed() else it }
    }
    // Pending delete: lambda to invoke on confirmation
    var pendingDelete by remember { mutableStateOf<(() -> Unit)?>(null) }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.unit_delete_take_title)) },
            confirmButton = {
                TextButton(onClick = { pendingDelete?.invoke(); pendingDelete = null }) {
                    Text(stringResource(Res.string.action_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(Res.string.action_no))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            ProjectManagementTopBar(
                onNewProjectClick = onNewProjectClick,
                onSettingsClick = onSettingsClick,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                uiState.workbook?.let { wb ->
                    Breadcrumb(
                        stringResource(
                            Res.string.pm_language_pair,
                            wb.target.resourceMetadata.language.anglicizedName,
                            wb.source.resourceMetadata.language.anglicizedName
                        ) to onLanguagesClick,
                        typeLabel(wb.target.resourceMetadata.identifier) to onBooksClick,
                        wb.target.title to onBackClick
                    )
                }
                SearchFilterBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(Res.string.pm_search_verses),
                    modifier = Modifier.weight(1f)
                )
            }
            // Search matches the label as shown ("Verse 3"), so the number alone or with the word works.
            val labels = units.associate { it.unit.sort to stringResource(Res.string.unit_verse_label, it.unit.title) }
            val shownUnits = units.filter { labels.getValue(it.unit.sort).contains(query.trim(), ignoreCase = true) }
            // Hoisted above the load-state branches so the scroll position is
            // retained across load transitions and back-navigation (e.g. returning
            // from recording a verse) instead of resetting to the top.
            val listState = rememberLazyListState()
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.error != null -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(Res.string.error_prefix, uiState.error ?: ""),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    CollapsibleCard(
                        title = stringResource(
                            Res.string.unit_chapter_title,
                            uiState.workbook?.target?.title.orEmpty(),
                            uiState.chapter?.title.orEmpty()
                        ),
                        isExpanded = isCardExpanded,
                        onToggle = { isCardExpanded = !isCardExpanded },
                        // Shrinks to its rows, but never past the screen: the rows scroll instead.
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        VerseSortHeader(sortDirection) {
                            sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                        }
                        if (shownUnits.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LazyColumnWithScrollbar(
                            state = listState,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            items(shownUnits, key = { it.unit.sort }) { unitHolder ->
                                val unit = unitHolder.unit
                                val takes = unit.audio.getAllTakes()
                                    .filter { !it.isDeleted() }
                                    .sortedBy { it.number }
                                val currentIndex = uiState.currentTakeIndices[unit.sort] ?: 0
                                val currentTake = takes.getOrNull(currentIndex)
                                // The take is "loaded" as long as the player is holding it
                                // (whether or not it's actively playing right now). Use *that*
                                // to gate slider/elapsed/duration values so a pause doesn't
                                // visually snap the cursor back to zero — only the play/pause
                                // icon depends on isPlaying. This mirrors how the chapter
                                // player gates its expanded panel on `isLoadedHere`.
                                val isThisUnitLoaded =
                                    uiState.currentPlayingTake?.file == currentTake?.file &&
                                        currentTake != null
                                val isThisUnitPlaying = isThisUnitLoaded && uiState.isPlaying

                                val precomputedDuration = currentTake?.file?.absolutePath
                                    ?.let { uiState.takeDurations[it] }
                                    ?: "00:00:00"

                                UnitCard(
                                    unit = unit,
                                    currentTake = currentTake,
                                    currentIndex = currentIndex,
                                    takes = takes,
                                    selectedTake = unitHolder.selectedTake,
                                    isExpanded = expandedUnitSort == unit.sort,
                                    onExpandClick = {
                                        expandedUnitSort = if (expandedUnitSort == unit.sort) null else unit.sort
                                    },
                                    isPlaying = isThisUnitPlaying,
                                    playbackProgress = if (isThisUnitLoaded) uiState.playbackProgress else 0f,
                                    elapsedText = if (isThisUnitLoaded) uiState.elapsedText else "00:00:00",
                                    durationText = if (isThisUnitLoaded) uiState.durationText else precomputedDuration,
                                    onPlayPause = { onPlayPause(unit) },
                                    onDelete = {
                                        if (currentTake != null) {
                                            pendingDelete = { onDelete(unit, currentTake) }
                                        }
                                    },
                                    onCycle = { direction -> onCycle(unit, direction) },
                                    onSelectTake = { onSelectTake(unit) },
                                    onOpenPlayback = {
                                        if (currentTake != null) onOpenPlayback(unit.sort, currentTake.number)
                                    },
                                    onRecord = { onUnitClick(unit.sort) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Verses (sortable) and Takes over the verse rows' columns; the last two slots are the mic and chevron columns. */
@Composable
private fun VerseSortHeader(direction: SortDirection, onSort: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SortHeaderCell(stringResource(Res.string.pm_sort_verses), isActive = true, direction = direction, onClick = onSort)
        }
        Text(
            text = stringResource(Res.string.pm_column_takes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
fun UnitCard(
    unit: Chunk,
    currentTake: Take?,
    currentIndex: Int,
    takes: List<Take>,
    /** The unit's selected take, passed in from UI state so the checkmark repaints when it changes
     *  (reading `unit.audio.getSelectedTake()` here instead is invisible to Compose). */
    selectedTake: Take?,
    isExpanded: Boolean,
    onExpandClick: () -> Unit,
    isPlaying: Boolean,
    playbackProgress: Float,
    elapsedText: String,
    durationText: String,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
    onCycle: (Int) -> Unit,
    onSelectTake: () -> Unit,
    onOpenPlayback: () -> Unit,
    onRecord: () -> Unit
) {
    val hasTakes = takes.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        // Tapping the row also expands it, once there is a take to show.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasTakes) Modifier.clickable { onExpandClick() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(Res.string.unit_verse_label, unit.title),
                style = MaterialTheme.typography.titleLarge,
                color = if (hasTakes) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (hasTakes) stringResource(Res.string.unit_take_count, takes.size) else "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = onRecord) {
                    Icon(Icons.Default.Mic, contentDescription = stringResource(Res.string.cd_record), modifier = Modifier.size(32.dp))
                }
            }
            IconButton(onClick = onExpandClick, enabled = hasTakes) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) stringResource(Res.string.action_collapse) else stringResource(Res.string.action_expand),
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Expanded body: the take player shared with the chapter list.
        if (isExpanded && currentTake != null) {
            TakePlayer(
                take = currentTake,
                position = currentIndex + 1,
                takeCount = takes.size,
                isSelected = currentTake.file == selectedTake?.file,
                onPrevious = { onCycle(-1) },
                onNext = { onCycle(1) },
                onSelect = onSelectTake,
                isPlaying = isPlaying,
                playbackProgress = playbackProgress,
                elapsedText = elapsedText,
                durationText = durationText,
                onPlayPause = onPlayPause,
                onDelete = onDelete,
                onOpenPlayback = onOpenPlayback,
                deleteDescription = stringResource(Res.string.cd_delete_take)
            )
        }
    }
}

@Preview
@Composable
fun UnitCardPreview() {
    val takes = listOf(
        MockData.createMockTake(1),
        MockData.createMockTake(2),
        MockData.createMockTake(3)
    )
    UnitCard(
        unit = MockData.createMockChunk(1, "1", hasAudio = true),
        currentTake = takes.first(),
        currentIndex = 0,
        takes = takes,
        selectedTake = takes.first(),
        isExpanded = true,
        onExpandClick = {},
        isPlaying = false,
        playbackProgress = 0.1f,
        elapsedText = "00:00:00",
        durationText = "00:00:05",
        onPlayPause = {},
        onDelete = {},
        onCycle = {},
        onSelectTake = {},
        onOpenPlayback = {},
        onRecord = {}
    )
}
