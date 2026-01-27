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
import androidx.compose.runtime.LaunchedEffect
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitListScreen(
    workbookSourceId: Int,
    workbookTargetId: Int,
    chapterNumber: Int,
    viewModel: UnitListViewModel = viewModel { UnitListViewModel() },
    onBackClick: () -> Unit,
    onUnitClick: (Int) -> Unit
) {

    LaunchedEffect(workbookSourceId, workbookTargetId, chapterNumber) {
        viewModel.loadUnits(workbookSourceId, workbookTargetId, chapterNumber)
    }

    val uiState by viewModel.uiState.collectAsState()
    val workbook = uiState.workbook
    val chapter = uiState.chapter

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("${workbook?.target?.title ?: ""} - Chapter ${chapter?.sort ?: ""}") },
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
                    items(uiState.units) { unit ->
                        ListItem(
                            headlineContent = { Text("Verse ${unit.sort}") }, // Assuming verses
                            modifier = Modifier.clickable { onUnitClick(unit.sort) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
