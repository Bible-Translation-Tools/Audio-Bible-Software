package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.MainMenuViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun MainMenuScreen(
    viewModel: MainMenuViewModel = viewModel { MainMenuViewModel() },
    onRecordClick: () -> Unit,
    onFilesClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .weight(0.33f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.secondary)
                .clickable { onFilesClick() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Files",
                modifier = Modifier.size(48.dp),
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier
                .weight(0.67f)
                .fillMaxHeight()
                .background(
                    if (uiState.hasActiveProject) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                .clickable { onRecordClick() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Record",
                modifier = Modifier.size(48.dp),
                tint = Color.White
            )

            if (uiState.hasActiveProject) {
                if (uiState.languageDisplay.isNotEmpty()) {
                    Text(
                        text = uiState.languageDisplay,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (uiState.bookDisplay.isNotEmpty()) {
                    Text(
                        text = uiState.bookDisplay,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (uiState.chapterDisplay.isNotEmpty()) {
                    Text(
                        text = uiState.chapterDisplay,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (uiState.unitDisplay.isNotEmpty()) {
                    Text(
                        text = uiState.unitDisplay,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun MainMenuPreview() {
    MainMenuScreen(onRecordClick = {}, onFilesClick = {})
}
