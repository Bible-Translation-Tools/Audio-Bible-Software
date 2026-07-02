package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.bttrecorder2.ui.components.LazyColumnWithScrollbar
import org.bibletranslationtools.bttrecorder2.ui.components.ProgressPieView
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterUiModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListUiState
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.jetbrains.compose.resources.stringResource
import btt_recorder2.composeapp.generated.resources.Res
import btt_recorder2.composeapp.generated.resources.chapter_compile_warning_title
import btt_recorder2.composeapp.generated.resources.chapter_compile_warning_message
import btt_recorder2.composeapp.generated.resources.action_ok
import btt_recorder2.composeapp.generated.resources.action_cancel
import btt_recorder2.composeapp.generated.resources.chapter_delete_take_title
import btt_recorder2.composeapp.generated.resources.chapter_delete_take_message
import btt_recorder2.composeapp.generated.resources.action_delete
import btt_recorder2.composeapp.generated.resources.action_back
import btt_recorder2.composeapp.generated.resources.cd_record_chapter
import btt_recorder2.composeapp.generated.resources.cd_delete_chapter_take
import btt_recorder2.composeapp.generated.resources.cd_open_in_playback
import btt_recorder2.composeapp.generated.resources.action_loading
import btt_recorder2.composeapp.generated.resources.error_prefix
import btt_recorder2.composeapp.generated.resources.cd_chapter_compiled
import btt_recorder2.composeapp.generated.resources.cd_compile_chapter
import btt_recorder2.composeapp.generated.resources.cd_compile_chapter_not_ready
import btt_recorder2.composeapp.generated.resources.action_collapse
import btt_recorder2.composeapp.generated.resources.cd_expand_chapter_take
import btt_recorder2.composeapp.generated.resources.action_pause
import btt_recorder2.composeapp.generated.resources.action_play
import btt_recorder2.composeapp.generated.resources.main_chapter_label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListScreen(
    viewModel: ChapterListViewModel = viewModel { ChapterListViewModel() },
    onBackClick: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onRecordChapter: (Int) -> Unit = {},
    onOpenChapterPlayback: (chapterSort: Int, takeNumber: Int) -> Unit = { _, _ -> }
) {
    LaunchedEffect(Unit) {
        viewModel.loadChapters()
    }

    val uiState by viewModel.uiState.collectAsState()

    // Confirmation-dialog targets. Both flows funnel through AlertDialogs so
    // destructive / heavy actions can't be triggered with a single tap.
    var pendingCompileChapter by remember { mutableStateOf<Chapter?>(null) }
    var pendingDeleteChapter by remember { mutableStateOf<Chapter?>(null) }

    ChapterListContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onChapterClick = onChapterClick,
        onRecordChapter = onRecordChapter,
        onCompileClick = { chapter -> pendingCompileChapter = chapter },
        onChapterExpand = viewModel::prepareChapterPlayback,
        onChapterPlayPause = viewModel::toggleChapterPlayback,
        onChapterDeleteRequest = { chapter -> pendingDeleteChapter = chapter },
        onOpenChapterPlayback = onOpenChapterPlayback
    )

    pendingCompileChapter?.let { chapter ->
        AlertDialog(
            onDismissRequest = { pendingCompileChapter = null },
            title = { Text(stringResource(Res.string.chapter_compile_warning_title)) },
            text = { Text(stringResource(Res.string.chapter_compile_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.compileChapter(chapter)
                    pendingCompileChapter = null
                }) { Text(stringResource(Res.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCompileChapter = null }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }

    pendingDeleteChapter?.let { chapter ->
        AlertDialog(
            onDismissRequest = { pendingDeleteChapter = null },
            title = { Text(stringResource(Res.string.chapter_delete_take_title)) },
            text = { Text(stringResource(Res.string.chapter_delete_take_message, chapter.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChapterTake(chapter)
                    pendingDeleteChapter = null
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteChapter = null }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListContent(
    uiState: ChapterListUiState,
    onBackClick: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onRecordChapter: (Int) -> Unit,
    onCompileClick: (Chapter) -> Unit = {},
    onChapterExpand: (Chapter) -> Unit = {},
    onChapterPlayPause: (Chapter) -> Unit = {},
    onChapterDeleteRequest: (Chapter) -> Unit = {},
    onOpenChapterPlayback: (chapterSort: Int, takeNumber: Int) -> Unit = { _, _ -> }
) {
    // The row a user taps to expand. Only one expanded at a time, mirroring
    // the original Recorder behaviour.
    var expandedChapterSort by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(uiState.workbook?.target?.title ?: stringResource(Res.string.action_loading)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
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
            // Hoisted above the `when` so the scroll position is retained across
            // load-state transitions and back-navigation (rememberLazyListState is
            // rememberSaveable-backed and restored per nav back-stack entry).
            val listState = rememberLazyListState()
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.error != null -> {
                    Text(
                        text = stringResource(Res.string.error_prefix, uiState.error ?: ""),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumnWithScrollbar(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.chapters, key = { it.chapter.sort }) { uiModel ->
                            val isLoadedHere =
                                uiState.loadedChapterSort == uiModel.chapter.sort
                            val isPlayingThis = isLoadedHere && uiState.isChapterPlaying
                            val isCompilingThis =
                                uiState.compilingChapterSort == uiModel.chapter.sort
                            val isExpanded =
                                expandedChapterSort == uiModel.chapter.sort &&
                                    uiModel.hasChapterTake

                            ChapterItem(
                                uiModel = uiModel,
                                isExpanded = isExpanded,
                                isCompiling = isCompilingThis,
                                isPlaying = isPlayingThis,
                                // Use the loaded-here values so the slider /
                                // duration label survive a pause (the take is
                                // still in the player). When a different chapter
                                // is loaded, fall back to zeros.
                                playbackProgress = if (isLoadedHere) uiState.playbackProgress else 0f,
                                elapsedText = if (isLoadedHere) uiState.elapsedText else "00:00:00",
                                durationText = if (isLoadedHere) uiState.durationText else "00:00:00",
                                onChapterClick = {
                                    // Tap on the row's text area opens the unit list, just like
                                    // the original. Expand/collapse is on the chevron.
                                    onChapterClick(uiModel.chapter.sort)
                                },
                                onRecordChapter = { onRecordChapter(uiModel.chapter.sort) },
                                onCompileClick = {
                                    // Compile only when every verse has a take; the chevron
                                    // (not this icon) drives expand of an existing take.
                                    if (uiModel.canCompile && !isCompilingThis) {
                                        onCompileClick(uiModel.chapter)
                                    }
                                },
                                onExpandToggle = {
                                    val nowExpanding =
                                        expandedChapterSort != uiModel.chapter.sort
                                    expandedChapterSort =
                                        if (nowExpanding) uiModel.chapter.sort else null
                                    if (nowExpanding && uiModel.hasChapterTake) {
                                        // Eagerly load the take so duration shows
                                        // immediately instead of after the first
                                        // play tap.
                                        onChapterExpand(uiModel.chapter)
                                    }
                                },
                                onPlayPause = { onChapterPlayPause(uiModel.chapter) },
                                onDelete = { onChapterDeleteRequest(uiModel.chapter) },
                                onOpenPlayback = {
                                    uiModel.chapterTakeNumber?.let { takeNumber ->
                                        onOpenChapterPlayback(uiModel.chapter.sort, takeNumber)
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChapterItem(
    uiModel: ChapterUiModel,
    isExpanded: Boolean,
    isCompiling: Boolean,
    isPlaying: Boolean,
    playbackProgress: Float,
    elapsedText: String,
    durationText: String,
    onChapterClick: () -> Unit,
    onRecordChapter: () -> Unit,
    onCompileClick: () -> Unit,
    onExpandToggle: () -> Unit,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
    onOpenPlayback: () -> Unit = {}
) {
    // Un-gray the chapter when it has any recorded audio — either a compiled/recorded
    // chapter take or at least one verse take.
    val hasAnyTake = uiModel.hasContent || uiModel.hasChapterTake
    val rowAlpha = if (hasAnyTake) 1f else 0.38f

    // The layers icon is a *compile* action, available ONLY when every verse has a
    // selected take (canCompile). The existence of a chapter take is irrelevant to
    // whether compile is offered — you can't compile a chapter without all its
    // verses, and a chapter take alone must not make it look available.
    //   - solid + enabled: every verse recorded → tap to (re)compile.
    //   - dimmed + disabled: some verse is missing a take.
    // Expand/collapse of an existing chapter take lives on the separate chevron.
    val layersActive = uiModel.canCompile
    val layersAlpha = if (layersActive) 1f else 0.38f
    val layersEnabled = uiModel.canCompile

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChapterClick() }
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Text(
                text = stringResource(Res.string.main_chapter_label, uiModel.chapter.title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (hasAnyTake) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha),
                modifier = Modifier.weight(1f)
            )

            ProgressPieView(
                progress = (uiModel.progress * 100).toInt(),
                modifier = Modifier.size(36.dp),
                progressColor = MaterialTheme.colorScheme.primary,
                backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = rowAlpha)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Compile/layers icon. Click semantics depend on chapter state —
            // see onCompileClick wiring in ChapterListContent.
            if (isCompiling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp).padding(2.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
            } else {
                IconButton(
                    onClick = onCompileClick,
                    enabled = layersEnabled,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = when {
                            uiModel.canCompile -> stringResource(Res.string.cd_compile_chapter)
                            uiModel.hasChapterTake -> stringResource(Res.string.cd_chapter_compiled)
                            else -> stringResource(Res.string.cd_compile_chapter_not_ready)
                        },
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = layersAlpha)
                    )
                }
            }

            // Record button — always shown
            IconButton(
                onClick = onRecordChapter,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = stringResource(Res.string.cd_record_chapter),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            // Expand chevron — only present once a chapter take exists, so it
            // doesn't suggest expandability on rows that have nothing to show.
            if (uiModel.hasChapterTake) {
                IconButton(
                    onClick = onExpandToggle,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        // Right chevron when collapsed (pointing at the row contents
                        // you'd reveal), down chevron when expanded.
                        imageVector = if (isExpanded) {
                            Icons.Default.KeyboardArrowDown
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                        contentDescription = if (isExpanded) stringResource(Res.string.action_collapse) else stringResource(Res.string.cd_expand_chapter_take),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                // Reserve the same width so neighboring rows align visually
                // regardless of compile state.
                Spacer(modifier = Modifier.size(40.dp))
            }
        }

        // Expanded body — playback row for the compiled chapter take. Visible
        // only when the user toggled it open and a chapter take exists.
        if (isExpanded) {
            ChapterTakePlaybackRow(
                isPlaying = isPlaying,
                playbackProgress = playbackProgress,
                elapsedText = elapsedText,
                durationText = durationText,
                onPlayPause = onPlayPause,
                onDelete = onDelete,
                onOpenPlayback = onOpenPlayback
            )
        }
    }
}

@Composable
private fun ChapterTakePlaybackRow(
    isPlaying: Boolean,
    playbackProgress: Float,
    elapsedText: String,
    durationText: String,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
    onOpenPlayback: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = elapsedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp)
            )
            Slider(
                value = playbackProgress.coerceIn(0f, 1f),
                onValueChange = {},
                modifier = Modifier.weight(1f),
                enabled = false
            )
            Text(
                text = durationText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(Res.string.cd_delete_chapter_take),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onOpenPlayback) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = stringResource(Res.string.cd_open_in_playback),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            FilledIconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(Res.string.action_pause) else stringResource(Res.string.action_play)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Preview
@Composable
fun ChapterItemPreview() {
    Column {
        ChapterItem(
            uiModel = ChapterUiModel(
                chapter = MockData.createMockChapter(1, "Chapter 1", "1"),
                hasContent = true,
                progress = 0.08f,
                hasChapterTake = false,
                canCompile = false
            ),
            isExpanded = false,
            isCompiling = false,
            isPlaying = false,
            playbackProgress = 0f,
            elapsedText = "00:00:00",
            durationText = "00:00:00",
            onChapterClick = {},
            onRecordChapter = {},
            onCompileClick = {},
            onExpandToggle = {},
            onPlayPause = {},
            onDelete = {}
        )
        HorizontalDivider()
        ChapterItem(
            uiModel = ChapterUiModel(
                chapter = MockData.createMockChapter(2, "Chapter 2", "2"),
                hasContent = false,
                progress = 0f,
                hasChapterTake = false,
                canCompile = false
            ),
            isExpanded = false,
            isCompiling = false,
            isPlaying = false,
            playbackProgress = 0f,
            elapsedText = "00:00:00",
            durationText = "00:00:00",
            onChapterClick = {},
            onRecordChapter = {},
            onCompileClick = {},
            onExpandToggle = {},
            onPlayPause = {},
            onDelete = {}
        )
    }
}

@Preview
@Composable
fun ChapterListContentPreview() {
    ChapterListContent(
        uiState = ChapterListUiState(
            chapters = listOf(
                ChapterUiModel(MockData.createMockChapter(1, "Chapter 1", "1"), hasContent = true, progress = 0.08f),
                ChapterUiModel(MockData.createMockChapter(2, "Chapter 2", "2"), hasContent = false, progress = 0f),
                ChapterUiModel(MockData.createMockChapter(3, "Chapter 3", "3"), hasContent = false, progress = 0f)
            ),
            workbook = null
        ),
        onBackClick = {},
        onChapterClick = {},
        onRecordChapter = {}
    )
}
