package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.MainMenuViewModel
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.cd_files
import org.bibletranslationtools.shared.resources.cd_record
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun MainMenuScreen(
    viewModel: MainMenuViewModel = viewModel { MainMenuViewModel() },
    onRecordClick: () -> Unit,
    onFilesClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
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
                imageVector = Icons.Rounded.Folder,
                contentDescription = stringResource(Res.string.cd_files),
                modifier = Modifier.size(100.dp),
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
                .clickable { onRecordClick() }
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = stringResource(Res.string.cd_record),
                modifier = Modifier.size(100.dp),
                tint = Color.White
            )

            if (uiState.hasActiveProject) {
                if (uiState.languageDisplay.isNotEmpty()) {
                    Text(
                        text = uiState.languageDisplay,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                if (uiState.bookDisplay.isNotEmpty()) {
                    Text(
                        text = uiState.bookDisplay,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                if (uiState.chapterDisplay.isNotEmpty()) {
                    Text(
                        text = uiState.chapterDisplay,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                if (uiState.unitDisplay.isNotEmpty()) {
                    Text(
                        text = uiState.unitDisplay,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center
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
