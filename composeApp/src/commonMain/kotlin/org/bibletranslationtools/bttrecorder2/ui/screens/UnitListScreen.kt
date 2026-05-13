package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListUiState
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.bibletranslationtools.bttrecorder2.ui.MockData

private val GreenPlay = Color(0xFF4CAF50)

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
            title = { Text("Delete take?") },
            confirmButton = {
                TextButton(onClick = { pendingDelete?.invoke(); pendingDelete = null }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("No")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("${uiState.workbook?.target?.title ?: ""} - Chapter ${uiState.chapter?.sort ?: ""}")
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRecordChapter) {
                        Icon(Icons.Default.Mic, contentDescription = "Record Chapter")
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
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.error != null) {
                Text(
                    text = "Error: ${uiState.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
    val selectedTake = unit.audio.getSelectedTake()
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
                text = "Verse ${unit.title}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (hasTakes) FontWeight.Bold else FontWeight.Normal,
                color = if (hasTakes)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.weight(1f)
            )

            if (hasTakes) {
                Text(
                    text = "×${takes.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            IconButton(
                onClick = onRecord,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Record")
            }

            if (hasTakes) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
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
            HorizontalDivider()

            // Seekbar row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = elapsedText,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = playbackProgress,
                    onValueChange = {},
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = GreenPlay,
                        activeTrackColor = GreenPlay,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                )
                Text(
                    text = durationText,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Action row: delete | waveform | play | select-take
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Take",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(onClick = onOpenPlayback) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "Open in Playback",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Play/pause is larger and green
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = GreenPlay,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = onSelectTake) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Select Take",
                        tint = if (currentTake == selectedTake)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Footer — grey background, "Take X of Y" + date
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 2.dp)
            ) {
                IconButton(onClick = { onCycle(-1) }) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous Take")
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Take ${currentIndex + 1} of ${takes.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Normal
                    )
                    val dateText = try {
                        val ts = currentTake.createdTimestamp
                        val monthName = ts.month.getDisplayName(
                            java.time.format.TextStyle.FULL,
                            java.util.Locale.getDefault()
                        )
                        "$monthName ${ts.dayOfMonth}, ${ts.year}"
                    } catch (_: Exception) { "" }
                    if (dateText.isNotEmpty()) {
                        Text(
                            text = dateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = { onCycle(1) }) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next Take")
                }
            }
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
