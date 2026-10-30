package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.vinceglb.filekit.PlatformFile
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementUiState

/**
 * The frame both project screens share: the title bar (with a back arrow when [onBackClick] is
 * given), New Project and the import/settings menu, and the loading and error states around
 * [content].
 */
@Composable
internal fun ProjectManagementScaffold(
    uiState: ProjectManagementUiState,
    onNewProjectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onImportProject: (PlatformFile) -> Unit,
    onBackClick: (() -> Unit)? = null,
    content: @Composable (ProjectManagementUiState.Success) -> Unit
) {
    Scaffold(
        topBar = {
            ProjectManagementTopBar(
                onNewProjectClick = onNewProjectClick,
                onSettingsClick = onSettingsClick,
                onImportProject = onImportProject,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (uiState) {
                is ProjectManagementUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ProjectManagementUiState.Success -> content(uiState)
                is ProjectManagementUiState.Error -> {
                    Text(
                        text = uiState.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}
