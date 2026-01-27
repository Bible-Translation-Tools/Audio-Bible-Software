package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.screens.wizard.BookSelectionScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.wizard.SourceSelectionScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.wizard.TargetLanguageSelectionScreen
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationUiState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.WizardStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWizardScreen(
    viewModel: ProjectCreationViewModel,
    onBackClick: () -> Unit,
    onProjectCreated: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Handle project created effect
    if (uiState.isCreated) {
        onProjectCreated()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("New Project") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.currentStep == WizardStep.SOURCE) {
                            onBackClick()
                        } else {
                            viewModel.navigateBack()
                        }
                    }) {
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
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                when (uiState.currentStep) {
                    WizardStep.SOURCE -> {
                        SourceSelectionScreen(
                            sources = uiState.sources,
                            onSourceSelected = { viewModel.selectSource(it) }
                        )
                    }
                    WizardStep.TARGET_LANGUAGE -> {
                        TargetLanguageSelectionScreen(
                            languages = uiState.targetLanguages,
                            onLanguageSelected = { viewModel.selectTarget(it) }
                        )
                    }
                    WizardStep.BOOK -> {
                        BookSelectionScreen(
                            books = uiState.availableBooks,
                            onBookSelected = { viewModel.selectBook(it) }
                        )
                    }
                }
            }
            
            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                )
            }
        }
    }
}
