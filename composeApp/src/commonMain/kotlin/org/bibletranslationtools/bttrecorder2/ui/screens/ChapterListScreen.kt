package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterUiModel
import androidx.compose.material.icons.filled.Build
import androidx.compose.foundation.layout.Row

import org.jetbrains.compose.ui.tooling.preview.Preview
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListScreen(
    workbookSourceId: Int,
    workbookTargetId: Int,
    viewModel: ChapterListViewModel = viewModel { ChapterListViewModel() },
    onBackClick: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onCompileClick: (Int) -> Unit = {}
) {

    LaunchedEffect(workbookSourceId, workbookTargetId) {
        viewModel.loadChapters(workbookSourceId, workbookTargetId)
    }

    val uiState by viewModel.uiState.collectAsState()

    ChapterListContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onChapterClick = onChapterClick,
        onCompileClick = onCompileClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListContent(
    uiState: ChapterListUiState,
    onBackClick: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onCompileClick: (Int) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(uiState.workbook?.target?.title ?: "Loading...") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
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
                    items(uiState.chapters) { uiModel ->
                        ChapterItem(
                            uiModel = uiModel,
                            onClick = { onChapterClick(uiModel.chapter.sort) },
                            onCompileClick = { onCompileClick(uiModel.chapter.sort) }
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
    onClick: () -> Unit,
    onCompileClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = uiModel.chapter.title,
                fontWeight = if (uiModel.hasContent) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
            )
        },
        trailingContent = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                 if (uiModel.hasContent) {
                    IconButton(onClick = onCompileClick) {
                        Icon(androidx.compose.material.icons.Icons.Default.Build, contentDescription = "Compile")
                    }
                }
                if (uiModel.progress > 0) {
                    CircularProgressIndicator(
                        progress = { uiModel.progress },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
}
@Preview
@Composable
fun ChapterItemPreview() {
    ChapterItem(
        uiModel = ChapterUiModel(
            chapter = MockData.createMockChapter(1, "Chapter 1", "1"),
            hasContent = true,
            progress = 0.5f
        ),
        onClick = {},
        onCompileClick = {}
    )
}

@Preview
@Composable
fun ChapterListContentPreview() {
    ChapterListContent(
        uiState = ChapterListUiState(
            chapters = listOf(
                ChapterUiModel(MockData.createMockChapter(1, "Chapter 1", "1"), true, 0.5f),
                ChapterUiModel(MockData.createMockChapter(2, "Chapter 2", "2"), false, 0f)
            ),
            workbook = null // Workbook is optional for title
        ),
        onBackClick = {},
        onChapterClick = {},
        onCompileClick = {}
    )
}
