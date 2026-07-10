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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.chapter
import org.bibletranslationtools.orature.resources.chapterTitle
import org.bibletranslationtools.orature.resources.goBack
import org.bibletranslationtools.orature.resources.narrationTitle
import org.bibletranslationtools.orature.resources.options
import org.bibletranslationtools.orature.resources.redo
import org.bibletranslationtools.orature.resources.undo
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.components.OratureAudioWorkspace
import org.bibletranslationtools.orature.ui.components.OratureChapterSelector
import org.bibletranslationtools.orature.ui.components.OratureNarrationToolBar
import org.bibletranslationtools.orature.ui.components.OratureTeleprompter
import org.bibletranslationtools.orature.ui.components.TeleprompterActions
import org.bibletranslationtools.orature.ui.viewmodels.OratureNarrationViewModel
import org.bibletranslationtools.otter.common.domain.narration.teleprompter.NarrationStateType
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

        Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
                uiState.error != null -> Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error
                )
                else -> NarrationBody(uiState, viewModel)
            }
        }
    }
}

/**
 * The narration page body (JVM: `NarrationPage`'s vbox): audio workspace (Phase 5b) over the
 * transport bar over the teleprompter. In Phase 5a the workspace is a placeholder and the
 * transport/record actions are inert — the teleprompter faithfully shows each verse's state.
 */
@Composable
private fun NarrationBody(
    uiState: org.bibletranslationtools.orature.ui.viewmodels.OratureNarrationUiState,
    viewModel: OratureNarrationViewModel
) {
    Column(modifier = Modifier.fillMaxSize().background(OratureColors.Background)) {
        // Audio workspace: the AudioScene composite (recorded chapter + live take) + verse
        // markers + centered playhead + volume bar. Reads the VM's double-buffered snapshot.
        OratureAudioWorkspace(
            waveformProvider = viewModel::currentWaveform,
            viewportsProvider = viewModel::currentViewports,
            splitPivotProvider = viewModel::currentSplitPivot,
            markerInfos = uiState.markerInfos,
            volumeProvider = viewModel::currentVolume,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.33f)
        )

        // Transport disabled only while actively recording (NOT while paused) — you can play
        // finished verses before the whole chapter is done. Play-all mid-recording is a rejected
        // state-machine transition (caught), but the audio still loads + plays.
        val state = uiState.narrationState
        val transportEnabled = uiState.actionsEnabled &&
            state != NarrationStateType.RECORDING &&
            state != NarrationStateType.RECORDING_AGAIN
        OratureNarrationToolBar(
            isPlaying = uiState.isPlaying,
            enabled = transportEnabled,
            onPlayPause = viewModel::onTogglePlayAll,
            onPrevious = viewModel::onSeekPreviousMarker,
            onNext = viewModel::onSeekNextMarker
        )

        OratureTeleprompter(
            verses = uiState.verses,
            highlightedIndex = uiState.highlightedVerseIndex,
            actions = TeleprompterActions(
                onRecord = viewModel::onRecord,
                onNext = viewModel::onNext,
                onPauseRecording = viewModel::onPauseRecording,
                onResumeRecording = viewModel::onResumeRecording,
                onRecordAgain = viewModel::onRecordAgain,
                onSave = viewModel::onSave,
                onPlay = viewModel::onPlayVerse,
                onPausePlayback = { viewModel.onPausePlayback() }
            ),
            actionsEnabled = uiState.actionsEnabled,
            modifier = Modifier.weight(1f)
        )
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
            .heightIn(min = 80.dp)
            .background(OratureColors.Foreground)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.goBack),
                tint = OratureColors.RegularText
            )
        }
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = OratureColors.RegularText
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
