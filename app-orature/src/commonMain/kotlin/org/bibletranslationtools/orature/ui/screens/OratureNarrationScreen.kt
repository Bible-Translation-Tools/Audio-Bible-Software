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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.vinceglb.filekit.path
import org.bibletranslationtools.orature.resources.`import`
import org.bibletranslationtools.orature.resources.editVerseMarkers
import org.bibletranslationtools.orature.resources.openChapterIn
import org.bibletranslationtools.orature.resources.options
import org.bibletranslationtools.orature.resources.redo
import org.bibletranslationtools.orature.resources.restartChapter
import org.bibletranslationtools.orature.resources.undo
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.components.OratureAudioWorkspace
import org.bibletranslationtools.orature.ui.components.OratureChapterSelector
import org.bibletranslationtools.orature.ui.components.OratureNarrationToolBar
import org.bibletranslationtools.orature.ui.components.OraturePluginOpenedCover
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
    onBack: () -> Unit,
    onOpenVerseMarkerEditor: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // The built-in Verse Marker editor opens as its own route once the VM has compiled the chapter
    // take and populated the handoff (JVM: the marker plugin window opening).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.openVerseMarkerEditor.collect { onOpenVerseMarkerEditor() }
    }

    // While a plugin is open, the whole page is replaced by the plugin-opened cover — no header,
    // no chapter selector, no teleprompter (JVM: `workspace.dock(pluginOpenedPage)` replaces the
    // ENTIRE page). Narration has no source-audio-player concept, so the cover always shows
    // "Source Audio Not Available" (sourceDurationMs = 0) — a real architectural fact, not a stub.
    if (uiState.isPluginOpen) {
        OraturePluginOpenedCover(
            contentTitle = "${uiState.bookTitle} ${uiState.activeChapterTitle}".trim(),
            sourceText = uiState.sourceText,
            sourceLicense = uiState.sourceLicense,
            isSourcePlaying = false,
            sourcePositionMs = 0,
            sourceDurationMs = 0,
            sourceRate = 1.0,
            onToggleSource = {},
            onSeekSource = {},
            onSetSourceRate = {}
        )
        return
    }

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
            canUndo = uiState.canUndo,
            canRedo = uiState.canRedo,
            canRestartChapter = uiState.canRestartChapter,
            editorConfigured = viewModel.editorConfiguredForChapter(),
            markerConfigured = viewModel.markerConfigured(),
            // Import is available except while actively recording/playing (JVM: NarrationMenu disableWhen).
            canImportChapterAudio = uiState.narrationState.let { s ->
                s != NarrationStateType.RECORDING && s != NarrationStateType.RECORDING_AGAIN &&
                    s != NarrationStateType.RECORDING_AGAIN_PAUSED && s != NarrationStateType.PLAYING
            },
            onBack = onBack,
            onUndo = viewModel::onUndo,
            onRedo = viewModel::onRedo,
            onRestartChapter = viewModel::onRestartChapter,
            onOpenChapterInEditor = viewModel::openChapterInEditor,
            onEditVerseMarkers = viewModel::editVerseMarkers,
            onImportChapterAudio = viewModel::onImportChapterAudio,
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
    // The verse marker menu's "Import" item (JVM: NarrationOpenImportAudioDialogEvent(verseIndex))
    // opens a file picker; the chosen file is spliced into whichever verse was pending when picked.
    var pendingImportVerseIndex by remember { mutableStateOf<Int?>(null) }
    val verseAudioPicker = io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher(
        type = io.github.vinceglb.filekit.dialogs.FileKitType.File(extensions = listOf("wav", "mp3")),
        mode = io.github.vinceglb.filekit.dialogs.FileKitMode.Single
    ) { file ->
        val index = pendingImportVerseIndex
        pendingImportVerseIndex = null
        if (file != null && index != null) viewModel.importVerseAudio(index, java.io.File(file.path))
    }

    Column(modifier = Modifier.fillMaxSize().background(OratureColors.Background)) {
        // Audio workspace: the AudioScene composite (recorded chapter + live take) + verse
        // markers + centered playhead + volume bar. Reads the VM's double-buffered snapshot.
        OratureAudioWorkspace(
            waveformProvider = viewModel::currentWaveform,
            viewportsProvider = viewModel::currentViewports,
            splitPivotProvider = viewModel::currentSplitPivot,
            markerInfos = uiState.markerInfos,
            volumeProvider = viewModel::currentVolume,
            positionProvider = viewModel::currentAudioPosition,
            totalFramesProvider = viewModel::currentTotalFrames,
            timelineProvider = viewModel::currentTimeline,
            peakCacheFor = viewModel::peakCacheFor,
            clock = viewModel.clock,
            waveformSampleRate = viewModel.waveformSampleRate(),
            isRecordingView = viewModel::isRecordingView,
            scrollEnabled = uiState.scrollEnabled,
            markersEditable = uiState.markersEditable,
            onSeekToFrame = viewModel::seekToFrame,
            onMarkerDragStart = viewModel::onStartMoveMarker,
            onMarkerDragEnd = viewModel::onFinishMoveMarker,
            onPlayVerse = viewModel::onPlayVerse,
            onRecordAgain = viewModel::onRecordAgain,
            onEditVerse = viewModel::editVerseExternally,
            onImportVerse = { index -> pendingImportVerseIndex = index; verseAudioPicker.launch() },
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
                onPausePlayback = { viewModel.onPausePlayback() },
                onEditExternally = viewModel::editVerseExternally
            ),
            actionsEnabled = uiState.actionsEnabled,
            canEditExternally = viewModel.editorConfigured(),
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
    canUndo: Boolean,
    canRedo: Boolean,
    canRestartChapter: Boolean,
    editorConfigured: Boolean,
    markerConfigured: Boolean,
    canImportChapterAudio: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRestartChapter: () -> Unit,
    onOpenChapterInEditor: () -> Unit,
    onEditVerseMarkers: () -> Unit,
    onImportChapterAudio: (String) -> Unit,
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

        // Undo / redo enabled by history + narration state (JVM: hasUndo/hasRedo && not mid-record).
        // Tint theme-aware (like the back arrow) so the icons stay visible on the dark header; dimmed
        // when disabled.
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                Icons.Filled.Undo,
                contentDescription = stringResource(Res.string.undo),
                tint = if (canUndo) OratureColors.RegularText else OratureColors.Disabled
            )
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(
                Icons.Filled.Redo,
                contentDescription = stringResource(Res.string.redo),
                tint = if (canRedo) OratureColors.RegularText else OratureColors.Disabled
            )
        }

        // Options menu (JVM: NarrationMenu). The button always opens; items gate themselves.
        Box {
            var menuOpen by remember { mutableStateOf(false) }
            // Audio-file picker for "Import Chapter Audio" (JVM: NarrationOpenImportAudioDialogEvent).
            val audioPicker = io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher(
                type = io.github.vinceglb.filekit.dialogs.FileKitType.File(extensions = listOf("wav", "mp3")),
                mode = io.github.vinceglb.filekit.dialogs.FileKitMode.Single
            ) { file -> file?.let { onImportChapterAudio(it.path) } }

            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(Res.string.options),
                    tint = OratureColors.RegularText
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                // Plugin items appear when a plugin of that type is configured, and are always
                // enabled — the chapter take is compiled on demand (JVM: no enableWhen on these).
                if (editorConfigured) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.openChapterIn)) },
                        onClick = { menuOpen = false; onOpenChapterInEditor() }
                    )
                }
                if (markerConfigured) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.editVerseMarkers)) },
                        onClick = { menuOpen = false; onEditVerseMarkers() }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.restartChapter)) },
                    enabled = canRestartChapter,
                    onClick = { menuOpen = false; onRestartChapter() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.`import`)) },
                    enabled = canImportChapterAudio,
                    onClick = { menuOpen = false; audioPicker.launch() }
                )
            }
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
