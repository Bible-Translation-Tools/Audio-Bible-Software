package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.chapter
import org.bibletranslationtools.orature.resources.chapterTitle
import org.bibletranslationtools.orature.resources.goBack
import org.bibletranslationtools.orature.resources.narrationTitle
import org.bibletranslationtools.orature.resources.options
import org.bibletranslationtools.orature.resources.redo
import org.bibletranslationtools.orature.resources.undo
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.components.OratureChapterSelector
import org.bibletranslationtools.orature.ui.viewmodels.OratureNarrationViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Orature's narration page SHELL (Phase 4): a faithful `NarrationHeader` (book title +
 * undo/redo/options + working chapter selector) over the shared open-project state. The
 * body (audio workspace / toolbar / teleprompter — JVM: `NarrationPage`'s vbox) is stubbed
 * with structural placeholders; Phase 5 fills it in.
 */
@Composable
fun OratureNarrationScreen(
    viewModel: OratureNarrationViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NarrationHeader(
            title = stringResource(Res.string.narrationTitle, uiState.bookTitle),
            chapterTitle = if (uiState.activeChapterSort != null) {
                stringResource(Res.string.chapterTitle, stringResource(Res.string.chapter), uiState.activeChapterTitle)
            } else "",
            hasPrevious = uiState.hasPreviousChapter,
            hasNext = uiState.hasNextChapter,
            chapters = uiState.chapters,
            onBack = onBack,
            onPrevious = viewModel::selectPreviousChapter,
            onNext = viewModel::selectNextChapter,
            onSelectChapter = viewModel::selectChapter
        )

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
                uiState.error != null -> Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error
                )
                // Phase 5 replaces this with AudioWorkspaceView / NarrationToolBar / TeleprompterView.
                else -> NarrationBodyPlaceholder()
            }
        }
    }
}

@Composable
private fun NarrationHeader(
    title: String,
    chapterTitle: String,
    hasPrevious: Boolean,
    hasNext: Boolean,
    chapters: List<org.bibletranslationtools.orature.ui.viewmodels.OratureChapterGridItem>,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectChapter: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.goBack))
        }
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp)
        )

        Spacer(Modifier.weight(1f))

        // Undo / redo / options mirror the real header but are inert until Phase 5 wires
        // the narration state machine (JVM: enabled by NarrationStateType).
        IconButton(onClick = {}, enabled = false) {
            Icon(Icons.Filled.Undo, contentDescription = stringResource(Res.string.undo))
        }
        IconButton(onClick = {}, enabled = false) {
            Icon(Icons.Filled.Redo, contentDescription = stringResource(Res.string.redo))
        }
        IconButton(onClick = {}, enabled = false) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.options))
        }

        if (chapters.isNotEmpty()) {
            OratureChapterSelector(
                chapterTitle = chapterTitle,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                chapters = chapters,
                onPrevious = onPrevious,
                onNext = onNext,
                onSelectChapter = onSelectChapter,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Structural stand-in for the narration body (audio workspace / toolbar / teleprompter). */
@Composable
private fun NarrationBodyPlaceholder() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(2f)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
        )
    }
}
