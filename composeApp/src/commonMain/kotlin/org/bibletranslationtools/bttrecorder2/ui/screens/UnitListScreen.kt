package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListViewModel

import org.bibletranslationtools.bttrecorder2.ui.MockData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitListScreen(
    workbookSourceId: Int,
    workbookTargetId: Int,
    chapterNumber: Int,
    viewModel: UnitListViewModel = viewModel { UnitListViewModel() },
    onBackClick: () -> Unit,
    onUnitClick: (Int) -> Unit,
    onRecordChapter: () -> Unit
) {

    LaunchedEffect(workbookSourceId, workbookTargetId, chapterNumber) {
        viewModel.loadUnits(workbookSourceId, workbookTargetId, chapterNumber)
    }

    val uiState by viewModel.uiState.collectAsState()

    UnitListContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onUnitClick = onUnitClick,
        onRecordChapter = onRecordChapter,
        onPlayPause = { viewModel.togglePlay(it) },
        onDelete = { unit, take -> viewModel.deleteTake(unit, take) },
        onCycle = { unit, direction -> viewModel.cycleTake(unit, direction) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitListContent(
    uiState: org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListUiState,
    onBackClick: () -> Unit,
    onUnitClick: (Int) -> Unit,
    onRecordChapter: () -> Unit,
    onPlayPause: (Chunk) -> Unit,
    onDelete: (Chunk, Take) -> Unit,
    onCycle: (Chunk, Int) -> Unit
) {
    // Track expanded unit
    var expandedUnitSort by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("${uiState.workbook?.target?.title ?: ""} - Chapter ${uiState.chapter?.sort ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRecordChapter) {
                        Icon(imageVector = Icons.Default.Mic, contentDescription = "Record Chapter")
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
                .background(MaterialTheme.colorScheme.background)
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
                        UnitCard(
                            unit = unitHolder.unit,
                            isExpanded = expandedUnitSort == unitHolder.unit.sort,
                            onExpandClick = {
                                expandedUnitSort = if (expandedUnitSort == unitHolder.unit.sort) null else unitHolder.unit.sort
                            },
                            isPlaying = uiState.isPlaying && uiState.currentPlayingTake?.file == unitHolder.unit.audio.getSelectedTake()?.file,
                            playbackProgress = if (uiState.isPlaying && uiState.currentPlayingTake?.file == unitHolder.unit.audio.getSelectedTake()?.file) uiState.playbackProgress else 0f,
                            onPlayPause = { onPlayPause(unitHolder.unit) },
                            onDelete = { take -> onDelete(unitHolder.unit, take) },
                            onCycle = { direction -> onCycle(unitHolder.unit, direction) },
                            onRecord = { onUnitClick(unitHolder.unit.sort) }
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
    isExpanded: Boolean,
    onExpandClick: () -> Unit,
    isPlaying: Boolean,
    playbackProgress: Float,
    onPlayPause: () -> Unit,
    onDelete: (Take) -> Unit,
    onCycle: (Int) -> Unit,
    onRecord: () -> Unit
) {
    val selectedTake = unit.audio.getSelectedTake()
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable { onExpandClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Verse ${unit.label}", // Use label or sort
                    style = MaterialTheme.typography.titleMedium
                )
                if (selectedTake != null) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = "Has Recording")
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                
                // Audio Controls
                if (selectedTake != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = { onCycle(-1) }) {
                            Icon(Icons.Default.KeyboardArrowLeft, "Previous Take")
                        }
                        
                        Text(text = "Take ${selectedTake.number}")
                        
                        IconButton(onClick = { onCycle(1) }) {
                            Icon(Icons.Default.KeyboardArrowRight, "Next Take")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = onPlayPause) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play"
                            )
                        }
                        
                        LinearProgressIndicator(
                            progress = { playbackProgress },
                            modifier = Modifier.weight(1f).height(8.dp),
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = { onDelete(selectedTake) }) {
                            Icon(Icons.Default.Delete, "Delete Take", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    Text("No recording selected", style = MaterialTheme.typography.bodyMedium)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onRecord,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Mic, "Record")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Record")
                }
            }
        }
    }
}

@Preview
@Composable
fun UnitCardPreview() {
    UnitCard(
        unit = MockData.createMockChunk(1, "1", hasAudio = true),
        isExpanded = true,
        onExpandClick = {},
        isPlaying = false,
        playbackProgress = 0.3f,
        onPlayPause = {},
        onDelete = {},
        onCycle = {},
        onRecord = {}
    )
}

//@Preview
//@Composable
//fun UnitListContentPreview() {
//    UnitListContent(
//        uiState = org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListUiState(
//            units = listOf(
//                MockData.createMockChunk(1, "1", hasAudio = true),
//                MockData.createMockChunk(2, "2", hasAudio = false)
//            ),
//            chapter = MockData.createMockChapter(1, "Chapter 1", "1"),
//            workbook = null
//        ),
//        onBackClick = {},
//        onUnitClick = {},
//        onPlayPause = {},
//        onDelete = { _, _ -> },
//        onCycle = { _, _ -> }
//    )
//}
