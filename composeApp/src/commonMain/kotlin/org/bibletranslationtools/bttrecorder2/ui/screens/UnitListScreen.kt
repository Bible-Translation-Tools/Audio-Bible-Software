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
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.bttrecorder2.ui.MockData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitListScreen(
    workbook: WorkbookDescriptor,
    chapterNumber: Int,
    onBackClick: () -> Unit,
    onUnitClick: (Int) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("${workbook.title} - Chapter $chapterNumber") },
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
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Text("Unit List Stub for Chapter $chapterNumber", style = MaterialTheme.typography.headlineMedium)
            // TODO: Implement actual unit list using real data
        }
    }
}

@Preview
@Composable
fun UnitListPreview() {
    UnitListScreen(
        workbook = MockData.mockWorkbooks[0],
        chapterNumber = 1,
        onBackClick = {},
        onUnitClick = {}
    )
}
