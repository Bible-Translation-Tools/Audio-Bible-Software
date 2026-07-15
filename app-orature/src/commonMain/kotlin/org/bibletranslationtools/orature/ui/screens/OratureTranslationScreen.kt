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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
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
import org.bibletranslationtools.orature.resources.need_source_audio
import org.bibletranslationtools.orature.resources.openIn
import org.bibletranslationtools.orature.resources.redo
import org.bibletranslationtools.orature.resources.source_audio_download_description__template
import org.bibletranslationtools.orature.resources.source_audio_missing_description
import org.bibletranslationtools.orature.resources.source_audio_missing_for
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
                onBack = onBack,
                onUndo = viewModel::onUndo,
                onRedo = viewModel::onRedo,
                onOpenIn = { /* Phase 8 */ },
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
    Row(modifier = Modifier.fillMaxSize()) {
        ChunkingStepsDrawer(
            selected = uiState.selectedStep,
            reachable = uiState.reachableStep,
            noSourceAudio = uiState.noSourceAudio,
            chunks = uiState.chunks,
            onSelectStep = viewModel::selectStep,
            onSelectChunk = viewModel::selectChunk
        )

        // Center: the current step's screen (Consume is built in 6b; others placeholder).
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().background(OratureColors.Background),
            contentAlignment = Alignment.Center
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
                ChunkingStep.PEER_EDIT -> {
                    val peerEditVm = androidx.lifecycle.viewmodel.compose.viewModel(key = "peeredit") {
                        org.bibletranslationtools.orature.ui.viewmodels.OraturePeerEditViewModel(viewModel)
                    }
                    OraturePeerEditScreen(peerEditVm)
                }
                else -> Text(
                    text = stringResource(uiState.selectedStep.title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = OratureColors.RegularText
                )
            }
        }

        // Right: source-text drawer — shown for peer-edit-and-later steps or when there's no
        // source audio (JVM: SourceTextDrawer.visibleWhen).
        val showSource = uiState.noSourceAudio ||
            uiState.selectedStep.ordinal >= ChunkingStep.PEER_EDIT.ordinal
        if (showSource) {
            SourceTextDrawer(uiState.sourceText)
        }
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
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenIn: () -> Unit,
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
        Text(text = title, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = OratureColors.RegularText)

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.Filled.Undo, contentDescription = stringResource(Res.string.undo))
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.Filled.Redo, contentDescription = stringResource(Res.string.redo))
        }
        if (canOpenIn) {
            IconButton(onClick = onOpenIn) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(Res.string.openIn))
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
    val width = if (collapsed) 64.dp else 260.dp

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(OratureColors.Foreground)
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
            IconButton(onClick = { collapsed = !collapsed }) {
                Icon(
                    if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
                    contentDescription = stringResource(if (collapsed) Res.string.expand else Res.string.collapse),
                    tint = OratureColors.RegularText
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (step in ChunkingStep.entries) {
                if (step == ChunkingStep.CHUNKING && noSourceAudio) continue
                StepRow(
                    step = step,
                    isSelected = step == selected,
                    isReachable = step.ordinal <= reachable.ordinal,
                    collapsed = collapsed,
                    onClick = { onSelectStep(step) }
                )
                // The chunk sub-list under the active chunk-using step (JVM: chunkListProperty).
                val usesChunks = step.ordinal >= ChunkingStep.BLIND_DRAFT.ordinal
                if (!collapsed && step == selected && usesChunks && chunks.isNotEmpty()) {
                    ChunkSubList(chunks, onSelectChunk)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChunkSubList(
    chunks: List<org.bibletranslationtools.orature.ui.viewmodels.OratureChunkViewData>,
    onSelectChunk: (Int) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (c in chunks) {
            val bg = when {
                c.selected -> OratureColors.Primary
                c.completed -> OratureColors.Primary.copy(alpha = 0.15f)
                else -> OratureColors.SurfaceSecondary
            }
            val fg = if (c.selected) OratureColors.OnPrimary else OratureColors.RegularText
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable { onSelectChunk(c.number) },
                contentAlignment = Alignment.Center
            ) {
                Text("${c.number}", color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun StepRow(
    step: ChunkingStep,
    isSelected: Boolean,
    isReachable: Boolean,
    collapsed: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) OratureColors.Primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent
    val textColor = when {
        isSelected -> OratureColors.Primary
        isReachable -> OratureColors.RegularText
        else -> OratureColors.RegularText.copy(alpha = 0.4f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (isReachable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Step index badge (or a lock for unreachable steps).
        if (!isReachable) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = textColor, modifier = Modifier.width(20.dp))
        } else {
            Text(
                text = "${step.ordinal + 1}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.width(20.dp)
            )
        }
        if (!collapsed) {
            Text(
                text = stringResource(step.title),
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor
            )
        }
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
            .background(OratureColors.Background)
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
                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
            ) {
                Icon(Icons.Filled.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(Res.string.check_online), modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = onGoToNarration, modifier = Modifier.weight(1f)) {
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

/** The right-hand source-text panel (JVM: `SourceTextDrawer`). */
@Composable
private fun SourceTextDrawer(sourceText: String) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(OratureColors.Foreground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = sourceText,
            fontSize = 15.sp,
            color = OratureColors.RegularText
        )
    }
}
