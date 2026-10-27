package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import org.bibletranslationtools.bttrecorder2.ui.components.LazyColumnWithScrollbar
import org.bibletranslationtools.bttrecorder2.ui.components.TakeBrowserPanel
import org.bibletranslationtools.bttrecorder2.ui.components.TakeCount
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListUiState
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.unit_delete_take_title
import org.bibletranslationtools.shared.resources.action_yes
import org.bibletranslationtools.shared.resources.action_no
import org.bibletranslationtools.shared.resources.unit_chapter_title
import org.bibletranslationtools.shared.resources.action_back
import org.bibletranslationtools.shared.resources.cd_record_chapter
import org.bibletranslationtools.shared.resources.cd_record
import org.bibletranslationtools.shared.resources.cd_delete_take
import org.bibletranslationtools.shared.resources.error_prefix
import org.bibletranslationtools.shared.resources.unit_verse_label
import org.bibletranslationtools.shared.resources.action_collapse
import org.bibletranslationtools.shared.resources.action_expand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitListScreen(
    viewModel: UnitListViewModel = viewModel { UnitListViewModel() },
    onBackClick: () -> Unit,
    onUnitClick: (Int) -> Unit,
    onRecordChapter: () -> Unit,
    onOpenPlayback: (unitSort: Int, takeNumber: Int) -> Unit
) {
    LaunchedEffect(Unit) {
        viewModel.loadUnits()
    }

    val uiState by viewModel.uiState.collectAsState()

    UnitListContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onUnitClick = onUnitClick,
        onRecordChapter = onRecordChapter,
        onPlayPause = { viewModel.togglePlay(it) },
        onDelete = { unit, take -> viewModel.deleteTake(unit, take) },
        onCycle = { unit, direction -> viewModel.cycleTake(unit, direction) },
        onSelectTake = { viewModel.selectCurrentTake(it) },
        onOpenPlayback = onOpenPlayback
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitListContent(
    uiState: UnitListUiState,
    onBackClick: () -> Unit,
    onUnitClick: (Int) -> Unit,
    onRecordChapter: () -> Unit,
    onPlayPause: (Chunk) -> Unit,
    onDelete: (Chunk, Take) -> Unit,
    onCycle: (Chunk, Int) -> Unit,
    onSelectTake: (Chunk) -> Unit,
    onOpenPlayback: (unitSort: Int, takeNumber: Int) -> Unit
) {
    var expandedUnitSort by remember { mutableStateOf<Int?>(null) }
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
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(Res.string.unit_chapter_title, uiState.workbook?.target?.title ?: "", (uiState.chapter?.sort?.toString() ?: "")))
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onRecordChapter) {
                        Icon(Icons.Default.Mic, contentDescription = stringResource(Res.string.cd_record_chapter))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Hoisted above the load-state branches so the scroll position is
            // retained across load transitions and back-navigation (e.g. returning
            // from recording a verse) instead of resetting to the top.
            val listState = rememberLazyListState()
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.error != null) {
                Text(
                    text = stringResource(Res.string.error_prefix, uiState.error ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumnWithScrollbar(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.units) { unitHolder ->
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
                        HorizontalDivider()
                    }
                }
            }
        }
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
        // Header row — tapping anywhere toggles expansion
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasTakes) Modifier.clickable { onExpandClick() } else Modifier)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Text(
                text = stringResource(Res.string.unit_verse_label, unit.title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (hasTakes) FontWeight.Bold else FontWeight.Normal,
                color = if (hasTakes)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.weight(1f)
            )

            if (hasTakes) {
                TakeCount(takes.size)
                Spacer(modifier = Modifier.width(8.dp))
            }

            IconButton(
                onClick = onRecord,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = stringResource(Res.string.cd_record))
            }

            if (hasTakes) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) stringResource(Res.string.action_collapse) else stringResource(Res.string.action_expand),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                // Reserve space so mic button stays aligned
                Spacer(modifier = Modifier.size(28.dp))
            }
        }

        // Expanded body
        if (isExpanded && currentTake != null) {
            TakeBrowserPanel(
                takes = takes,
                currentIndex = currentIndex,
                selectedTake = selectedTake,
                isPlaying = isPlaying,
                playbackProgress = playbackProgress,
                elapsedText = elapsedText,
                durationText = durationText,
                deleteDescription = Res.string.cd_delete_take,
                onPlayPause = onPlayPause,
                onDelete = onDelete,
                onOpenPlayback = onOpenPlayback,
                onSelectTake = onSelectTake,
                onCycle = onCycle
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
