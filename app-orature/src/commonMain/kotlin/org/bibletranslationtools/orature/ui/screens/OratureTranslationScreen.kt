package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.begin_narrating_book
import org.bibletranslationtools.orature.resources.chapter
import org.bibletranslationtools.orature.resources.chapterTitle
import org.bibletranslationtools.orature.resources.check_online
import org.bibletranslationtools.orature.resources.choose_file
import org.bibletranslationtools.orature.resources.cancel
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.`continue`
import org.bibletranslationtools.orature.resources.overridingSource
import org.bibletranslationtools.orature.resources.warning
import org.bibletranslationtools.orature.resources.collapse
import org.bibletranslationtools.orature.resources.drag_drop_or_browse_import__template
import org.bibletranslationtools.orature.resources.expand
import org.bibletranslationtools.orature.resources.file_extension_supported
import org.bibletranslationtools.orature.resources.goBack
import org.bibletranslationtools.orature.resources.importFailed
import org.bibletranslationtools.orature.resources.importing
import org.bibletranslationtools.orature.resources.licenseStatement
import org.bibletranslationtools.orature.resources.need_source_audio
import org.bibletranslationtools.orature.resources.openIn
import org.bibletranslationtools.orature.resources.redo
import org.bibletranslationtools.orature.resources.source_audio_download_description__template
import org.bibletranslationtools.orature.resources.source_audio_missing_description
import org.bibletranslationtools.orature.resources.source_audio_missing_for
import org.bibletranslationtools.orature.resources.sourceText
import org.bibletranslationtools.orature.resources.steps
import org.bibletranslationtools.orature.resources.undo
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.components.OratureChapterSelector
import org.bibletranslationtools.orature.ui.translation.ChunkingStep
import org.bibletranslationtools.orature.ui.viewmodels.OratureImportState
import org.bibletranslationtools.orature.ui.viewmodels.OratureImportViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureTranslationUiState
import org.bibletranslationtools.orature.ui.viewmodels.OratureTranslationViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * The oral-translation page (JVM: `ChunkingTranslationPage`): a header over a row of the
 * [ChunkingStepsDrawer] (left) + the current step's screen (center) + the source-text drawer
 * (right). Phase 6a builds the shell with placeholder step bodies; Consume (6b) and Chunking (6c)
 * fill the center.
 */
@Composable
fun OratureTranslationScreen(
    viewModel: OratureTranslationViewModel,
    onBack: () -> Unit,
    onGoToNarration: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // Source-audio import (Phase 9 partial) — reachable from the SourceAudioMissing "Choose File".
    val importVm = viewModel { OratureImportViewModel() }
    val importState by importVm.importState.collectAsState()
    val importPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("orature", "zip", "tstudio")),
        mode = FileKitMode.Single
    ) { file -> file?.let(importVm::importFile) }
    LaunchedEffect(importState) {
        if (importState is OratureImportState.Success) {
            viewModel.refresh() // source audio may now exist — recompute noSourceAudio
            importVm.acknowledge()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.pluginOpen) {
            // Full-bleed: JVM's `workspace.dock(pluginOpenedPage)` replaces the ENTIRE
            // `ChunkingTranslationPage` — header, steps drawer, and source-text drawer included —
            // leaving only `RootView`'s AppBar (our nav rail, rendered outside this screen
            // entirely by OratureRootShell) still visible. Whichever step actually opened the
            // plugin (Blind Draft, Peer Edit, Final Review) reuses the SAME `viewModel(key = ...)`
            // instance TranslationStepContent's normal path already created, and shows its OWN
            // plugin-opened cover internally once ITS `isPluginOpen` flips.
            TranslationStepContent(uiState, viewModel, onGoToNarration, onChooseFile = { importPicker.launch() })
        } else {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                TranslationHeader(
                    title = uiState.bookTitle,
                    chapterTitle = if (uiState.activeChapterSort != null) {
                        stringResource(Res.string.chapterTitle, stringResource(Res.string.chapter), uiState.activeChapterTitle)
                    } else "",
                    hasPrevious = uiState.hasPreviousChapter,
                    hasNext = uiState.hasNextChapter,
                    chapters = uiState.chapters,
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    canOpenIn = uiState.selectedStep == ChunkingStep.FINAL_REVIEW,
                    pluginOpen = uiState.pluginOpen,
                    onBack = onBack,
                    onUndo = viewModel::onUndo,
                    onRedo = viewModel::onRedo,
                    onOpenIn = viewModel::onOpenIn,
                    onPrevious = viewModel::selectPreviousChapter,
                    onNext = viewModel::selectNextChapter,
                    onSelectChapter = viewModel::selectChapter
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
                        uiState.error != null -> Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                        else -> TranslationBody(uiState, viewModel, onGoToNarration, onChooseFile = { importPicker.launch() })
                    }
                }
            }
        }

        // Import progress scrim.
        if (importState is OratureImportState.InProgress) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0x80000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = OratureColors.Primary)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(Res.string.importing), color = Color.White)
                }
            }
        }
        // Conflict: imported source matches an existing one but with a different version +
        // versification — confirm overwrite (JVM: ExistingSourceImporter onRequestUserInput).
        if (importState is OratureImportState.ConflictPrompt) {
            AlertDialog(
                onDismissRequest = { importVm.resolveConflict(false) },
                confirmButton = {
                    TextButton(onClick = { importVm.resolveConflict(true) }) {
                        Text(stringResource(Res.string.`continue`))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { importVm.resolveConflict(false) }) {
                        Text(stringResource(Res.string.cancel))
                    }
                },
                title = { Text(stringResource(Res.string.warning)) },
                text = { Text(stringResource(Res.string.overridingSource)) }
            )
        }
        (importState as? OratureImportState.Error)?.let { err ->
            AlertDialog(
                onDismissRequest = importVm::acknowledge,
                confirmButton = {
                    TextButton(onClick = importVm::acknowledge) { Text(stringResource(Res.string.close)) }
                },
                title = { Text(stringResource(Res.string.importFailed)) },
                text = { Text(err.message) }
            )
        }
    }
}

@Composable
private fun TranslationBody(
    uiState: OratureTranslationUiState,
    viewModel: OratureTranslationViewModel,
    onGoToNarration: () -> Unit,
    onChooseFile: () -> Unit
) {
    // `TranslationBody` only renders when `!uiState.pluginOpen` (see the top-level branch in
    // OratureTranslationScreen), so the drawer/source-panel are never shown alongside a plugin
    // cover — no separate disabling needed here.
    Row(modifier = Modifier.fillMaxSize()) {
        ChunkingStepsDrawer(
            selected = uiState.selectedStep,
            reachable = uiState.reachableStep,
            noSourceAudio = uiState.noSourceAudio,
            chunks = uiState.chunks,
            onSelectStep = viewModel::selectStep,
            onSelectChunk = viewModel::selectChunk
        )

        // Center: the current step's screen (JVM: `.translation-view` — white, not the app's
        // light-gray page background).
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().background(OratureColors.Foreground),
            contentAlignment = Alignment.Center
        ) {
            TranslationStepContent(uiState, viewModel, onGoToNarration, onChooseFile)
        }

        // Right: source-text drawer — shown for peer-edit-and-later steps or when there's no
        // source audio (JVM: SourceTextDrawer.visibleWhen).
        val showSource = uiState.noSourceAudio ||
            uiState.selectedStep.ordinal >= ChunkingStep.PEER_EDIT.ordinal
        if (showSource) {
            SourceTextDrawer(uiState.sourceTitle, uiState.sourceText, uiState.sourceLicense, uiState.highlightedVerseLabel)
        }
    }
}

/**
 * The current step's screen (JVM: `translation-view` center content) — extracted so it can be
 * rendered EITHER inside [TranslationBody] (normal path, with the steps drawer + source-text
 * drawer as siblings) OR full-bleed, alone, when a plugin is open (see the top-level branch in
 * [OratureTranslationScreen]). Whichever step is active shows its OWN plugin-opened cover
 * internally once its VM's `isPluginOpen` flips — this function doesn't need to know that.
 */
@Composable
private fun TranslationStepContent(
    uiState: OratureTranslationUiState,
    viewModel: OratureTranslationViewModel,
    onGoToNarration: () -> Unit,
    onChooseFile: () -> Unit
) {
    when (uiState.selectedStep) {
        ChunkingStep.CONSUME_AND_VERBALIZE -> {
            val sort = uiState.activeChapterSort
            when {
                uiState.noSourceAudio -> SourceAudioMissing(
                    chapterTitle = uiState.activeChapterTitle,
                    bookTitle = uiState.bookTitle,
                    onGoToNarration = onGoToNarration,
                    onChooseFile = onChooseFile
                )
                sort != null -> {
                    // Keyed to the chapter so switching chapters rebuilds the source player.
                    val consumeVm = androidx.lifecycle.viewmodel.compose.viewModel(key = "consume-$sort") {
                        org.bibletranslationtools.orature.ui.viewmodels.OratureConsumeViewModel(sort)
                    }
                    OratureConsumeScreen(consumeVm)
                }
            }
        }
        ChunkingStep.CHUNKING -> {
            val sort = uiState.activeChapterSort
            if (sort != null && !uiState.noSourceAudio) {
                val chunkingVm = androidx.lifecycle.viewmodel.compose.viewModel(key = "chunking-$sort") {
                    org.bibletranslationtools.orature.ui.viewmodels.OratureChunkingViewModel(sort, viewModel)
                }
                // Step-leave persistence is handled by the translation VM's awaited chunk-save
                // handler (selectStep), which commits BEFORE loading the next step. A second
                // fire-and-forget save here would race it and corrupt the write, so it's gone.
                OratureChunkingScreen(chunkingVm)
            }
        }
        ChunkingStep.BLIND_DRAFT -> {
            val blindDraftVm = androidx.lifecycle.viewmodel.compose.viewModel(key = "blinddraft") {
                org.bibletranslationtools.orature.ui.viewmodels.OratureBlindDraftViewModel(viewModel)
            }
            OratureBlindDraftScreen(blindDraftVm)
        }
        // Peer Edit, Keyword Check, and Verse Check are the same review screen (JVM: all
        // find<PeerEdit>()); the VM applies a different checking status per step. Key by step
        // so switching steps re-inits with the right target status.
        ChunkingStep.PEER_EDIT, ChunkingStep.KEYWORD_CHECK, ChunkingStep.VERSE_CHECK -> {
            val peerEditVm = androidx.lifecycle.viewmodel.compose.viewModel(key = "review-${uiState.selectedStep}") {
                org.bibletranslationtools.orature.ui.viewmodels.OraturePeerEditViewModel(viewModel)
            }
            OraturePeerEditScreen(peerEditVm)
        }
        ChunkingStep.FINAL_REVIEW -> {
            val reviewVm = androidx.lifecycle.viewmodel.compose.viewModel(key = "finalreview") {
                org.bibletranslationtools.orature.ui.viewmodels.OratureChapterReviewViewModel(viewModel)
            }
            OratureChapterReviewScreen(reviewVm)
        }
        else -> Text(
            text = stringResource(uiState.selectedStep.title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = OratureColors.RegularText
        )
    }
}

/** A single 1dp vertical rule (JVM's per-side `-fx-border-width` — Compose's `border()` modifier
 *  only draws all four sides, so single-edge borders are a thin colored Box sibling instead). */
@Composable
private fun VerticalRule(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(1.dp).fillMaxHeight().background(color))
}

/** A rounded-square bordered icon button (JVM: `.btn.btn--tertiary` — 2dp border, 12dp corner
 *  radius). Same shape as `OratureBlindDraftScreen`'s tertiary buttons, duplicated here to keep
 *  this file's header controls self-contained. */
@Composable
private fun TertiaryIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, OratureColors.SurfaceTertiary, RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun TranslationHeader(
    title: String,
    chapterTitle: String,
    hasPrevious: Boolean,
    hasNext: Boolean,
    chapters: List<org.bibletranslationtools.orature.ui.viewmodels.OratureChapterGridItem>,
    canUndo: Boolean,
    canRedo: Boolean,
    canOpenIn: Boolean,
    pluginOpen: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenIn: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectChapter: (Int) -> Unit
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .background(OratureColors.Foreground)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.alpha(if (pluginOpen) 0.5f else 1f)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.goBack),
                tint = OratureColors.RegularText
            )
        }
        Text(text = title, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = OratureColors.RegularText)

        Spacer(Modifier.weight(1f))

        TertiaryIconButton(onClick = onUndo, enabled = canUndo, contentDescription = stringResource(Res.string.undo)) {
            Icon(
                Icons.Filled.Undo,
                contentDescription = null,
                tint = if (canUndo) OratureColors.RegularText else OratureColors.RegularText.copy(alpha = 0.3f)
            )
        }
        TertiaryIconButton(onClick = onRedo, enabled = canRedo, contentDescription = stringResource(Res.string.redo)) {
            Icon(
                Icons.Filled.Redo,
                contentDescription = null,
                tint = if (canRedo) OratureColors.RegularText else OratureColors.RegularText.copy(alpha = 0.3f)
            )
        }
        if (canOpenIn) {
            TertiaryIconButton(onClick = onOpenIn, contentDescription = stringResource(Res.string.openIn)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = OratureColors.RegularText)
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
                modifier = Modifier.padding(start = 8.dp).alpha(if (pluginOpen) 0.5f else 1f)
            )
        }
    }
    // JVM: `.top-navigation-pane { -fx-border-width: 0 0 1 0; -fx-border-color: -wa-surface-border; }`
    HorizontalDivider(color = OratureColors.SurfaceTertiary)
  }
}

/**
 * The left steps navigation (JVM: `ChunkingStepsDrawer`): a collapsible panel listing the workflow
 * steps. Reachable steps are clickable; later ones are locked. CHUNKING is hidden with no source
 * audio.
 */
@Composable
private fun ChunkingStepsDrawer(
    selected: ChunkingStep,
    reachable: ChunkingStep,
    noSourceAudio: Boolean,
    chunks: List<org.bibletranslationtools.orature.ui.viewmodels.OratureChunkViewData>,
    onSelectStep: (ChunkingStep) -> Unit,
    onSelectChunk: (Int) -> Unit
) {
    var collapsed by remember { mutableStateOf(false) }
    // JVM: `.chunking-step-drawer { -fx-pref-width: 360; }` / `:collapsed { -fx-max-width: 80; }`.
    val width = if (collapsed) 80.dp else 360.dp

  Row(modifier = Modifier.fillMaxHeight()) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(OratureColors.Foreground)
    ) {
        Row(
            // JVM: `.chunking-step-drawer__header-section { -fx-padding: 16; }` (same rule as
            // each step row's header section).
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!collapsed) {
                Text(
                    text = stringResource(Res.string.steps),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OratureColors.RegularText.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.weight(1f))
            // JVM: `.btn--icon` — a rounded square (12dp corners) with a 1dp border, not a bare
            // borderless icon button.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(OratureColors.Foreground, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .border(1.dp, OratureColors.SurfaceTertiary, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable { collapsed = !collapsed },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
                    contentDescription = stringResource(if (collapsed) Res.string.expand else Res.string.collapse),
                    tint = OratureColors.RegularText80
                )
            }
        }
        // JVM: `.chunking-step-drawer__header-section` shares the same bottom-rule spec as each
        // step row's header section.
        HorizontalDivider(color = OratureColors.BtnIconBorderColor)

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (step in ChunkingStep.entries) {
                if (step == ChunkingStep.CHUNKING && noSourceAudio) continue
                StepRow(
                    step = step,
                    isSelected = step == selected,
                    // Completed is relative to the currently SELECTED step (JVM: completedProperty =
                    // step.ordinal < selectedStep.ordinal), independent of how far the project has
                    // actually reached — stepping back to review an earlier step still shows the
                    // later, not-yet-visited steps with their own icon, not locked.
                    isCompleted = step.ordinal < selected.ordinal,
                    // Reachable gates whether the step can be opened at all (JVM: unavailableProperty
                    // = reachable.ordinal < step.ordinal) — locked + grayed out row.
                    isReachable = step.ordinal <= reachable.ordinal,
                    collapsed = collapsed,
                    onClick = { onSelectStep(step) }
                )
                // The chunk sub-list under the active chunk-using step (JVM: chunkListProperty).
                // Final Review is chapter-level (activeChunk = null), so it has no chunk sub-list.
                val usesChunks = step.ordinal >= ChunkingStep.BLIND_DRAFT.ordinal &&
                    step != ChunkingStep.FINAL_REVIEW
                if (!collapsed && step == selected && usesChunks && chunks.isNotEmpty()) {
                    ChunkSubList(chunks, onSelectChunk)
                }
            }
        }
    }
    // JVM: `.chunking-step-drawer { -fx-border-width: 0 1 0 0; -fx-border-color: -wa-surface-border; }`
    VerticalRule(OratureColors.SurfaceTertiary)
  }
}

private const val CHUNK_GRID_COLUMNS = 3

/**
 * The active step's chunk grid (JVM: `ChunkGrid`) — a 3-column grid of chunk cells, each an icon +
 * number. Default: an outlined bookmark, gray. Completed (not selected): a filled check-circle,
 * primary blue. Selected: a filled blue pill with a white bookmark (or check-circle, if also
 * completed) and white number.
 */
@Composable
private fun ChunkSubList(
    chunks: List<org.bibletranslationtools.orature.ui.viewmodels.OratureChunkViewData>,
    onSelectChunk: (Int) -> Unit
) {
  // JVM's `.chunking-step__content-section` CSS declares a 1px border, but its color
  // (-wa-surface-border, #e6e6e6) is nearly indistinguishable from the white background, and the
  // reference render shows no visible box around the chunk grid — just the numbered chunks
  // floating with spacing. No border here, matching what's actually visible.
  Box(modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (row in chunks.chunked(CHUNK_GRID_COLUMNS)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in row) {
                    val icon = when {
                        c.completed -> Icons.Filled.CheckCircle
                        c.selected -> Icons.Filled.Bookmark
                        else -> Icons.Filled.BookmarkBorder
                    }
                    val fg = when {
                        c.selected -> OratureColors.OnPrimary
                        c.completed -> OratureColors.Primary
                        else -> OratureColors.RegularText80
                    }
                    val bg = if (c.selected) OratureColors.Primary else androidx.compose.ui.graphics.Color.Transparent
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                            .clickable { onSelectChunk(c.number) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                        Text(
                            "${c.number}",
                            color = fg,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                // Pad the last row so its cells don't stretch wider than the earlier rows.
                repeat(CHUNK_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
  }
}

/** The step's own icon (JVM: `ChunkingStepNode.getStepperIcon`). */
private fun stepIcon(step: ChunkingStep): ImageVector = when (step) {
    ChunkingStep.CONSUME_AND_VERBALIZE -> Icons.Filled.Hearing
    ChunkingStep.CHUNKING -> Icons.Filled.ContentCut
    ChunkingStep.BLIND_DRAFT -> Icons.Filled.Headset
    ChunkingStep.PEER_EDIT -> Icons.Filled.Group
    ChunkingStep.KEYWORD_CHECK -> Icons.Filled.Edit
    ChunkingStep.VERSE_CHECK -> Icons.AutoMirrored.Filled.MenuBook
    ChunkingStep.FINAL_REVIEW -> Icons.Filled.PlayArrow
}

@Composable
private fun StepRow(
    step: ChunkingStep,
    isSelected: Boolean,
    isCompleted: Boolean,
    isReachable: Boolean,
    collapsed: Boolean,
    onClick: () -> Unit
) {
    // JVM: `.chunking-step:disabled { background: -wa-surface-secondary }` for locked steps.
    val bg = if (!isReachable) OratureColors.SurfaceSecondary else androidx.compose.ui.graphics.Color.Transparent
    val textColor = when {
        isCompleted -> OratureColors.StatusComplete
        isSelected -> OratureColors.Primary
        !isReachable -> OratureColors.RegularText80
        else -> OratureColors.RegularText
    }
    val icon = when {
        !isReachable -> Icons.Filled.Lock
        isCompleted -> Icons.Filled.CheckCircle
        else -> stepIcon(step)
    }
  Column {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (isReachable) Modifier.clickable(onClick = onClick) else Modifier)
            // JVM: `.chunking-step__header-section { -fx-padding: 16; }` — uniform on all sides.
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(24.dp))
        if (!collapsed) {
            Text(
                text = stringResource(step.title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
    // JVM: `.chunking-step__header-section { border-width: 1; border-color: transparent transparent
    // -wa-btn-icon-border-color transparent; }` — a bottom rule under each step row.
    HorizontalDivider(color = OratureColors.BtnIconBorderColor)
  }
}

/**
 * Shown for the Consume step when the chapter has no source audio (JVM: `SourceAudioMissing`):
 * a title + description, an import drop-area (import itself lands in Phase 9), a "need source
 * audio?" section linking to the GL audio site, and actions to check online or start narrating
 * this book instead.
 */
@Composable
private fun SourceAudioMissing(
    chapterTitle: String,
    bookTitle: String,
    onGoToNarration: () -> Unit,
    onChooseFile: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val downloadUrl = "https://audio.bibleineverylanguage.org/gl"
    val linkColor = OratureColors.Primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OratureColors.Foreground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(Res.string.source_audio_missing_for, chapterTitle),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = OratureColors.RegularText,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.source_audio_missing_description),
            fontSize = 15.sp,
            color = OratureColors.RegularText,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp)
        )
        Spacer(Modifier.height(24.dp))

        // Import drop-area (visual; drag-drop + browse import arrive in Phase 9).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .border(1.dp, OratureColors.SurfaceTertiary, RoundedCornerShape(8.dp))
                .background(OratureColors.Foreground, RoundedCornerShape(8.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = OratureColors.RegularText.copy(alpha = 0.6f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            // "Drag and Drop or {Choose File} to import" — the link is the format placeholder.
            val dndRaw = stringResource(Res.string.drag_drop_or_browse_import__template)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dndRaw.substringBefore("%1\$s"), fontSize = 15.sp, color = OratureColors.RegularText)
                Text(
                    text = stringResource(Res.string.choose_file),
                    fontSize = 15.sp,
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(onClick = onChooseFile)
                )
                Text(dndRaw.substringAfter("%1\$s"), fontSize = 15.sp, color = OratureColors.RegularText)
            }
            Spacer(Modifier.height(4.dp))
            // ".orature files supported." — the format placeholder is the bold extension.
            val extRaw = stringResource(Res.string.file_extension_supported)
            Row {
                Text(extRaw.substringBefore("%1\$s"), fontSize = 13.sp, color = OratureColors.RegularText.copy(alpha = 0.7f))
                Text("orature", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OratureColors.RegularText.copy(alpha = 0.7f))
                Text(extRaw.substringAfter("%1\$s"), fontSize = 13.sp, color = OratureColors.RegularText.copy(alpha = 0.7f))
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(modifier = Modifier.widthIn(max = 640.dp))
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(Res.string.need_source_audio),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = OratureColors.RegularText
        )
        Spacer(Modifier.height(8.dp))
        // "...found online at {link}. ..." — the link is the format placeholder.
        val dlRaw = stringResource(Res.string.source_audio_download_description__template)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 640.dp)) {
            Text(
                dlRaw.substringBefore("%1\$s"),
                fontSize = 15.sp,
                color = OratureColors.RegularText,
                textAlign = TextAlign.Center
            )
            Text(
                text = "audio.bibleineverylanguage.org",
                fontSize = 15.sp,
                color = linkColor,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { uriHandler.openUri(downloadUrl) }
            )
            Text(
                dlRaw.substringAfter("%1\$s"),
                fontSize = 15.sp,
                color = OratureColors.RegularText,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { uriHandler.openUri(downloadUrl) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
            ) {
                Icon(Icons.Filled.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(Res.string.check_online), modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = onGoToNarration, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(Res.string.begin_narrating_book, bookTitle))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp)
                )
            }
        }
    }
}

/** A single verse line parsed out of the raw source text (JVM: `RollingTextCell.buildChunkText`'s
 *  `^\d{1,3}(-\d*)?\.` split of `ProjectFilesAccessor`'s `"N. text"`-formatted lines). */
private val VERSE_MARKER_REGEX = Regex("""^\d{1,3}(-\d*)?\.""")

/** A verse line parsed out of the raw source text, keyed by its verse-number label so it can be
 *  matched against [SourceTextDrawer]'s `highlightedVerseLabel`. */
private data class SourceVerseLine(val label: String, val body: String)

/** The right-hand source-text panel (JVM: `SourceTextDrawer` wrapping `RollingSourceText` /
 *  `RollingTextCell` — a title heading, verse-numbered body, and license footer).
 *  [highlightedVerseLabel] is the current playhead's verse (JVM: `highlightedChunk`/
 *  `highlightedIndexProperty`, fed from whichever step is active via `TranslationViewModel2.
 *  currentMarkerProperty`) — the matching verse row is tinted and scrolled into view. */
@Composable
private fun SourceTextDrawer(
    sourceTitle: String,
    sourceText: String,
    sourceLicense: String,
    highlightedVerseLabel: String? = null
) {
  // JVM: `.source-text-drawer { -fx-max-width: 360/90; -fx-min-width: 360/90; -fx-border-width: 0 0 0 1;
  // -fx-border-color: -wa-surface-border; }` (:collapsed pseudo-class shrinks to 90).
  var collapsed by remember { mutableStateOf(false) }
  Row(modifier = Modifier.fillMaxHeight()) {
    VerticalRule(OratureColors.SurfaceTertiary)
    Column(
        modifier = Modifier
            .then(if (collapsed) Modifier.widthIn(min = 90.dp) else Modifier.width(360.dp))
            .fillMaxHeight()
            .background(OratureColors.Foreground)
    ) {
        // Header (JVM: `.source-text-drawer__header-section { -fx-padding: 16; -fx-border-width: 0 0 1 0; }`)
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!collapsed) {
                Text(
                    text = stringResource(Res.string.sourceText),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = OratureColors.RegularText80,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            // JVM: one `.btn.btn--tertiary` button — NOT `.btn--icon`, so it has no fixed
            // square size; it wraps its two-icon graphic (chevron + book), coming out as a
            // wide rounded rectangle rather than a square. Graphic swaps between
            // chevron-right+book (expanded) and chevron-left+book (collapsed), toggling a
            // LOCAL collapse state distinct from the steps-drawer's own collapse.
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .border(2.dp, OratureColors.SurfaceTertiary, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { collapsed = !collapsed }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    if (collapsed) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight,
                    contentDescription = stringResource(if (collapsed) Res.string.expand else Res.string.collapse),
                    tint = OratureColors.RegularText,
                    modifier = Modifier.size(20.dp)
                )
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = OratureColors.RegularText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        HorizontalDivider(color = OratureColors.BtnIconBorderColor)

        if (!collapsed) {
            // Verses (JVM: TEXT cells — one per `\n`-split line, verse number stripped via regex).
            // Title/license stay as fixed header/footer around the (auto-scrolling) verse list —
            // a deliberate simplification of JVM's single ListView, where title/verses/license are
            // all list items together; the highlight-follows-playhead behavior is what matters here.
            val verses = remember(sourceText) {
                sourceText.split("\n").filter { it.isNotEmpty() }.map { line ->
                    val marker = VERSE_MARKER_REGEX.find(line)?.value ?: ""
                    SourceVerseLine(marker.removeSuffix("."), line.substringAfter(marker).trim())
                }
            }
            val listState = rememberLazyListState()
            LaunchedEffect(highlightedVerseLabel, verses) {
                val target = verses.indexOfFirst { it.label == highlightedVerseLabel }
                if (target >= 0) runCatching { listState.animateScrollToItem(target) }
            }

            // Title (JVM: TITLE cell — `.h4.h4--80.source-content__info-text`)
            if (sourceTitle.isNotEmpty()) {
                Text(
                    text = sourceTitle,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = OratureColors.RegularText80,
                    modifier = Modifier.padding(top = 10.dp, start = 15.dp, end = 15.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(verses) { verse ->
                    // JVM: `.source-content__text:highlighted` / `.source-content__chunk:highlighted
                    // .label` — both the verse-number and body text tint to `-wa-highlight-text`.
                    val highlighted = verse.label.isNotEmpty() && verse.label == highlightedVerseLabel
                    val textColor = if (highlighted) OratureColors.Primary else OratureColors.RegularText
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (verse.label.isNotEmpty()) {
                            Text(
                                text = verse.label,
                                fontSize = 11.sp,
                                color = if (highlighted) OratureColors.Primary else OratureColors.RegularText80,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Text(
                            text = verse.body,
                            fontSize = 15.sp,
                            color = textColor
                        )
                    }
                }
            }

            // License (JVM: LICENSE cell — `.source-content__license-text`)
            if (sourceLicense.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.licenseStatement, sourceLicense),
                    fontSize = 16.sp,
                    fontStyle = FontStyle.Italic,
                    color = OratureColors.NoteText,
                    modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 16.dp)
                )
            }
        }
    }
  }
}
