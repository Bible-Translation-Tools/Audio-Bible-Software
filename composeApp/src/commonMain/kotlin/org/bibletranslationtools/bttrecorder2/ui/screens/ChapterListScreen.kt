package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.bttrecorder2.ui.components.ProgressPieView
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterUiModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListUiState
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.bibletranslationtools.bttrecorder2.ui.MockData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListScreen(
    viewModel: ChapterListViewModel = viewModel { ChapterListViewModel() },
    onBackClick: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onRecordChapter: (Int) -> Unit = {}
) {
    LaunchedEffect(Unit) {
        viewModel.loadChapters()
    }

    val uiState by viewModel.uiState.collectAsState()

    ChapterListContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onChapterClick = onChapterClick,
        onRecordChapter = onRecordChapter
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListContent(
    uiState: ChapterListUiState,
    onBackClick: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onRecordChapter: (Int) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(uiState.workbook?.target?.title ?: "Loading...") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                    items(uiState.chapters) { uiModel ->
                        ChapterItem(
                            uiModel = uiModel,
                            onChapterClick = { onChapterClick(uiModel.chapter.sort) },
                            onRecordChapter = { onRecordChapter(uiModel.chapter.sort) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun ChapterItem(
    uiModel: ChapterUiModel,
    onChapterClick: () -> Unit,
    onRecordChapter: () -> Unit
) {
    val hasContent = uiModel.hasContent
    val contentAlpha = if (hasContent) 1f else 0.38f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChapterClick() }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Text(
            text = uiModel.chapter.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (hasContent) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            modifier = Modifier.weight(1f)
        )

        ProgressPieView(
            progress = (uiModel.progress * 100).toInt(),
            modifier = Modifier.size(36.dp),
            progressColor = MaterialTheme.colorScheme.primary,
            backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = contentAlpha)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Compile/layers icon — always shown, greyed when no content
        Icon(
            Icons.Default.Layers,
            contentDescription = "Compile",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            modifier = Modifier
                .size(36.dp)
                .padding(6.dp)
        )

        // Record button — always shown
        IconButton(
            onClick = onRecordChapter,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = "Record Chapter",
                tint = MaterialTheme.colorScheme.onSurface
            )
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
                progress = 0.08f
            ),
            onChapterClick = {},
            onRecordChapter = {}
        )
        HorizontalDivider()
        ChapterItem(
            uiModel = ChapterUiModel(
                chapter = MockData.createMockChapter(2, "Chapter 2", "2"),
                hasContent = false,
                progress = 0f
            ),
            onChapterClick = {},
            onRecordChapter = {}
        )
    }
}

@Preview
@Composable
fun ChapterListContentPreview() {
    ChapterListContent(
        uiState = ChapterListUiState(
            chapters = listOf(
                ChapterUiModel(MockData.createMockChapter(1, "Chapter 1", "1"), true, 0.08f),
                ChapterUiModel(MockData.createMockChapter(2, "Chapter 2", "2"), false, 0f),
                ChapterUiModel(MockData.createMockChapter(3, "Chapter 3", "3"), false, 0f)
            ),
            workbook = null
        ),
        onBackClick = {},
        onChapterClick = {},
        onRecordChapter = {}
    )
}
