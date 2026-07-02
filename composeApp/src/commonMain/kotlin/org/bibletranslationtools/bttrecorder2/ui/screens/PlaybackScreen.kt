package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import org.bibletranslationtools.bttrecorder2.ui.platform.PlatformBackHandler
import org.bibletranslationtools.bttrecorder2.ui.playback.SourceAudioPlayerController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.bttrecorder2.ui.theme.TranslationRecorderTheme
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.MarkerKind
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.PlaybackViewModel
import org.jetbrains.compose.resources.stringResource
import btt_recorder2.composeapp.generated.resources.Res
import btt_recorder2.composeapp.generated.resources.playback_unsaved_title
import btt_recorder2.composeapp.generated.resources.playback_unsaved_message
import btt_recorder2.composeapp.generated.resources.action_save
import btt_recorder2.composeapp.generated.resources.action_discard
import btt_recorder2.composeapp.generated.resources.action_cancel
import btt_recorder2.composeapp.generated.resources.action_back
import btt_recorder2.composeapp.generated.resources.cd_verse_marker_mode
import btt_recorder2.composeapp.generated.resources.cd_rerecord
import btt_recorder2.composeapp.generated.resources.cd_insert_recording
import btt_recorder2.composeapp.generated.resources.cd_minimap
import btt_recorder2.composeapp.generated.resources.cd_source_audio
import btt_recorder2.composeapp.generated.resources.cd_skip_backward
import btt_recorder2.composeapp.generated.resources.cd_skip_forward
import btt_recorder2.composeapp.generated.resources.cd_play_pause
import btt_recorder2.composeapp.generated.resources.cd_save_as_new_take
import btt_recorder2.composeapp.generated.resources.cd_exit_verse_marker_mode
import btt_recorder2.composeapp.generated.resources.cd_place_verse_marker
import btt_recorder2.composeapp.generated.resources.cd_done_save_markers
import btt_recorder2.composeapp.generated.resources.edit_redo
import btt_recorder2.composeapp.generated.resources.edit_undo
import btt_recorder2.composeapp.generated.resources.edit_clear
import btt_recorder2.composeapp.generated.resources.edit_in
import btt_recorder2.composeapp.generated.resources.edit_out
import btt_recorder2.composeapp.generated.resources.edit_cut
import btt_recorder2.composeapp.generated.resources.ic_start_marker
import btt_recorder2.composeapp.generated.resources.ic_out_marker
import btt_recorder2.composeapp.generated.resources.ic_clear_markers
import btt_recorder2.composeapp.generated.resources.ic_cut
import btt_recorder2.composeapp.generated.resources.ic_startmarker_cyan
import btt_recorder2.composeapp.generated.resources.ic_endmarker_cyan
import org.jetbrains.compose.resources.painterResource
import btt_recorder2.composeapp.generated.resources.take_label
import btt_recorder2.composeapp.generated.resources.main_chapter_label
import btt_recorder2.composeapp.generated.resources.main_verse_label
import btt_recorder2.composeapp.generated.resources.source_audio_none
import btt_recorder2.composeapp.generated.resources.cd_pause_source
import btt_recorder2.composeapp.generated.resources.cd_play_source
import btt_recorder2.composeapp.generated.resources.playback_markers_remaining_label

@Composable
fun PlaybackScreen(
    viewModel: PlaybackViewModel,
    onBackClick: () -> Unit,
    onNavigateToRecorder: (sourceId: Int, targetId: Int, chapterNumber: Int, unitNumber: Int) -> Unit = { _, _, _, _ -> }
) {
    val ui by viewModel.uiState.collectAsState()
    var waveformWidth by remember { mutableStateOf(0) }
    var showExitConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(waveformWidth) {
        if (waveformWidth > 0) viewModel.setWaveformWidth(waveformWidth)
    }

    // A successfully saved take is terminal for this screen: return to the unit
    // list (matching the original PlaybackActivity, which finishes after a save).
    LaunchedEffect(viewModel) {
        viewModel.editedTakeSavedEvents.collectLatest {
            onBackClick()
        }
    }

    // The VM owns the back/accept decisions (it consults the edit session and
    // mode); the view only maps the resulting navigation intents to concrete
    // navigation or to the confirm dialog.
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is PlaybackViewModel.NavEvent.Rerecord ->
                    onNavigateToRecorder(event.sourceId, event.targetId, event.chapterNumber, event.unitNumber)
                is PlaybackViewModel.NavEvent.Insert ->
                    onNavigateToRecorder(event.sourceId, event.targetId, event.chapterNumber, event.unitNumber)
                is PlaybackViewModel.NavEvent.Exit -> onBackClick()
                is PlaybackViewModel.NavEvent.ConfirmExit -> showExitConfirm = true
            }
        }
    }

    // Route system/hardware back (Android) through the VM's decision, same as the
    // on-screen back affordance. No-op on platforms without a system back gesture.
    PlatformBackHandler(enabled = true, onBack = viewModel::onBackRequested)

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(Res.string.playback_unsaved_title)) },
            text = { Text(stringResource(Res.string.playback_unsaved_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    // Save emits editedTakeSavedEvents, which navigates back.
                    viewModel.saveCurrentEditsAsNewTake()
                }) { Text(stringResource(Res.string.action_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showExitConfirm = false
                        viewModel.exitWithoutSaving()
                    }) { Text(stringResource(Res.string.action_discard)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showExitConfirm = false }) { Text(stringResource(Res.string.action_cancel)) }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.cleanup() }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF202020))) {

        // ── Top bar ──────────────────────────────────────────────────────────
        if (ui.isVerseMarkerMode) {
            MarkerCounterBar(
                versesRemaining = ui.versesRemaining,
                onExit = viewModel::exitVerseMarkerMode
            )
        } else {
            PlaybackFileBar(
                targetUi = ui.targetUi,
                currentTakeLabel = ui.currentTakeNumber?.let { stringResource(Res.string.take_label, it) } ?: "",
                onBackClick = viewModel::onBackRequested,
                onVerseMarkerMode = viewModel::enterVerseMarkerMode,
                onRerecord = viewModel::onRerecord,
                onInsert = viewModel::onInsert,
                hasTake = ui.selectedTake != null
            )
        }

        // ── Waveform ─────────────────────────────────────────────────────────
        val textMeasurer = rememberTextMeasurer()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { waveformWidth = it.width }
        ) {
            PlaybackWaveform(
                samples = ui.waveformSamples,
                markerFrames = ui.markerFrames,
                markerLabels = ui.markerLabels,
                markerKinds = ui.markerKinds,
                currentFrame = ui.currentFrame,
                sampleRate = ui.sampleRate,
                selectionStartProgress = ui.selectionStartProgress,
                selectionEndProgress = ui.selectionEndProgress,
                durationFrames = ui.durationFrames,
                textMeasurer = textMeasurer,
                onSelectionStartMoved = viewModel::setSelectionStartAtProgress,
                onSelectionEndMoved = viewModel::setSelectionEndAtProgress,
                onVerseMarkerDragStart = viewModel::beginVerseMarkerDrag,
                onVerseMarkerMove = viewModel::moveVerseMarker,
                onVerseMarkerDragEnd = viewModel::endVerseMarkerDrag,
                onFreezeFollow = viewModel::freezePlaybackFollow,
                onResumeFollow = viewModel::resumePlaybackFollow,
                onScrubMove = viewModel::scrubToFrame,
                onScrubEnd = viewModel::endWaveformScrub,
                modifier = Modifier.fillMaxSize()
            )
            if (ui.error != null) {
                Text(
                    text = ui.error ?: "",
                    color = TranslationRecorderTheme.strongRed,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                )
            }
        }

        // ── Minimap / source audio ────────────────────────────────────────────
        val sourceAudioState by viewModel.sourceAudioState.collectAsState()
        MinimapWidget(
            showMinimap = ui.showMinimap,
            minimapSamples = ui.minimapSamples,
            progress = ui.progress,
            markerFrames = ui.markerFrames,
            durationFrames = ui.durationFrames,
            sampleRate = ui.sampleRate,
            textMeasurer = textMeasurer,
            selectionStartProgress = ui.selectionStartProgress,
            selectionEndProgress = ui.selectionEndProgress,
            onSeek = viewModel::seekToProgress,
            onShowMinimap = { viewModel.showMinimap(true) },
            onShowSource = { viewModel.showMinimap(false) },
            sourceAudioState = sourceAudioState,
            onSourceTogglePlayPause = viewModel::toggleSourcePlayback,
            onSourceSeek = viewModel::seekSourceToProgress,
            onWidthChanged = viewModel::setMinimapWidth
        )

        // ── Transport / edit bar ──────────────────────────────────────────────
        if (ui.isVerseMarkerMode) {
            MarkerToolbar(
                isPlaying = ui.isPlaying,
                elapsedText = ui.elapsedText,
                durationText = ui.durationText,
                onSeekBackward = viewModel::seekBackward,
                onPlayPause = viewModel::togglePlayPause,
                onSeekForward = viewModel::seekForward,
                onDropMarker = viewModel::dropVerseMarkerAtCurrentPosition,
                onDone = viewModel::saveVerseMarkers
            )
        } else {
            PlaybackTools(
                isPlaying = ui.isPlaying,
                elapsedText = ui.elapsedText,
                durationText = ui.durationText,
                hasStart = ui.selectionStartProgress != null,
                hasEnd = ui.selectionEndProgress != null,
                hasCuts = ui.canUndoEdit,
                canCut = ui.canCutSelection,
                // The accept/check button is always available, matching the
                // original's save button. The VM decides what it does (save edits
                // as a new take, or just accept and exit).
                canSave = true,
                onSeekBackward = viewModel::seekBackward,
                onPlayPause = viewModel::togglePlayPause,
                onSeekForward = viewModel::seekForward,
                onMarkStart = viewModel::markSelectionStartAtCurrent,
                onMarkEnd = viewModel::markSelectionEndAtCurrent,
                onCut = viewModel::cutSelection,
                onClear = viewModel::clearSelection,
                canRedo = ui.canRedoEdit,
                onUndo = viewModel::undoEdit,
                onRedo = viewModel::redoEdit,
                onSave = viewModel::acceptTake
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// File Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlaybackFileBar(
    targetUi: PlaybackViewModel.TargetUiState,
    currentTakeLabel: String,
    onBackClick: () -> Unit,
    onVerseMarkerMode: () -> Unit,
    onRerecord: () -> Unit,
    onInsert: () -> Unit,
    hasTake: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(TranslationRecorderTheme.veryDarkGray1)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: back button + breadcrumb labels — takes all remaining width so
        // the action icons are pushed flush to the right edge.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(Res.string.action_back), tint = Color.White)
            }
            if (targetUi.languageLabel.isNotEmpty()) {
                Text(
                    text = targetUi.languageLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
            }
            if (targetUi.projectLabel.isNotEmpty()) {
                Text(
                    text = targetUi.projectLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
            }
            if (targetUi.bookLabel.isNotEmpty()) {
                Text(
                    text = targetUi.bookLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
            }
            if (targetUi.chapterValue.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.main_chapter_label, targetUi.chapterValue),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
            }
            if (targetUi.unitValue.isNotEmpty() && targetUi.unitValue != "0") {
                Text(
                    text = stringResource(Res.string.main_verse_label, targetUi.unitValue),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }

        // Right: take label + action icons — no weight, so they sit at the end.
        if (currentTakeLabel.isNotEmpty()) {
            Text(
                text = currentTakeLabel,
                color = TranslationRecorderTheme.gray0,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        IconButton(
            onClick = onVerseMarkerMode,
            enabled = hasTake,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Default.BookmarkBorder, contentDescription = stringResource(Res.string.cd_verse_marker_mode), tint = Color.White)
        }
        IconButton(onClick = onRerecord, enabled = hasTake, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.cd_rerecord), tint = Color.White)
        }
        IconButton(onClick = onInsert, enabled = hasTake, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Mic, contentDescription = stringResource(Res.string.cd_insert_recording), tint = Color.White)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Waveform
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlaybackWaveform(
    samples: FloatArray,
    markerFrames: List<Int>,
    markerLabels: List<String>,
    markerKinds: List<MarkerKind> = emptyList(),
    currentFrame: Int,
    sampleRate: Int,
    selectionStartProgress: Float?,
    selectionEndProgress: Float?,
    durationFrames: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    onSelectionStartMoved: (Float) -> Unit = {},
    onSelectionEndMoved: (Float) -> Unit = {},
    onVerseMarkerDragStart: (index: Int) -> Unit = {},
    onVerseMarkerMove: (progress: Float) -> Unit = {},
    onVerseMarkerDragEnd: () -> Unit = {},
    onFreezeFollow: () -> Unit = {},
    onResumeFollow: () -> Unit = {},
    onScrubMove: (frame: Int) -> Unit = {},
    onScrubEnd: (frame: Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val framesOnScreen = (sampleRate.coerceAtLeast(1) * 10).toFloat()

    val currentFrameState = rememberUpdatedState(currentFrame)
    val selStartProgressState = rememberUpdatedState(selectionStartProgress)
    val selEndProgressState = rememberUpdatedState(selectionEndProgress)
    val durationFramesState = rememberUpdatedState(durationFrames)
    val markerFramesState = rememberUpdatedState(markerFrames)
    val onSelectionStartMovedState = rememberUpdatedState(onSelectionStartMoved)
    val onSelectionEndMovedState = rememberUpdatedState(onSelectionEndMoved)
    val onVerseMarkerDragStartState = rememberUpdatedState(onVerseMarkerDragStart)
    val onVerseMarkerMoveState = rememberUpdatedState(onVerseMarkerMove)
    val onVerseMarkerDragEndState = rememberUpdatedState(onVerseMarkerDragEnd)
    val onFreezeFollowState = rememberUpdatedState(onFreezeFollow)
    val onResumeFollowState = rememberUpdatedState(onResumeFollow)
    val onScrubMoveState = rememberUpdatedState(onScrubMove)
    val onScrubEndState = rememberUpdatedState(onScrubEnd)

    val startMarkerPainter = painterResource(Res.drawable.ic_startmarker_cyan)
    val endMarkerPainter = painterResource(Res.drawable.ic_endmarker_cyan)

    val dragModifier = modifier.pointerInput(framesOnScreen) {
        var activeMarker: String? = null  // "start", "end", "verse:N", or null = waveform scroll

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touchX = down.position.x
            val w = size.width.toFloat().coerceAtLeast(1f)
            // Integer frames-per-pixel matching the renderer + Canvas, centered on
            // the playhead, so marker hit-testing and scrubbing use the exact same
            // frame↔pixel mapping as what's drawn.
            val framesPerPixelI = ((sampleRate.coerceAtLeast(1) * 10) / w.toInt().coerceAtLeast(1)).coerceAtLeast(1)
            // Center frame captured at grab; the drag scrolls relative to this
            // anchor so playback advancing mid-drag can't shift the target.
            val anchorFrame = currentFrameState.value

            fun fToX(frame: Float) = w / 2f + (frame - anchorFrame) / framesPerPixelI

            val dur = durationFramesState.value
            val startX = selStartProgressState.value?.takeIf { dur > 0 }?.let { fToX(it * dur) }
            val endX = selEndProgressState.value?.takeIf { dur > 0 }?.let { fToX(it * dur) }
            val verseXs = markerFramesState.value.map { fToX(it.toFloat()) }

            // 48px touch target around each marker; start/end take priority over verse
            activeMarker = when {
                startX != null && kotlin.math.abs(touchX - startX) < 48f -> "start"
                endX != null && kotlin.math.abs(touchX - endX) < 48f -> "end"
                else -> {
                    val vIdx = verseXs.indexOfFirst { kotlin.math.abs(touchX - it) < 48f }
                    if (vIdx >= 0) "verse:$vIdx" else null
                }
            }

            if (activeMarker != null) {
                // Marker drag — no touch slop, respond immediately. Freeze the
                // playback follow first so the waveform (and its leftFrame) stays
                // put under the finger while dragging during playback; otherwise
                // the ticker keeps re-centering and the marker drifts away.
                down.consume()
                onFreezeFollowState.value()

                val verseIdx = activeMarker?.removePrefix("verse:")?.toIntOrNull()
                if (verseIdx != null) onVerseMarkerDragStartState.value(verseIdx)

                while (true) {
                    val event = awaitPointerEvent()
                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                    ch.consume()
                    if (!ch.pressed) break
                    // Inverse of fToX: frame under the finger, centered on the playhead.
                    val newFrame = anchorFrame + (ch.position.x - w / 2f) * framesPerPixelI
                    val progress = if (dur > 0) (newFrame / dur.toFloat()).coerceIn(0f, 1f) else 0f
                    when {
                        activeMarker == "start" -> onSelectionStartMovedState.value(progress)
                        activeMarker == "end" -> onSelectionEndMovedState.value(progress)
                        verseIdx != null -> onVerseMarkerMoveState.value(progress)
                    }
                }

                if (verseIdx != null) onVerseMarkerDragEndState.value()
                onResumeFollowState.value()
            } else {
                // Waveform scroll — live scrub. Wait for touch slop, then re-center
                // (and re-render) on every move so the waveform scrolls smoothly
                // under the fixed playhead. Audio keeps playing; the seek commits
                // on release.
                val firstDrag = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() }
                    ?: return@awaitEachGesture
                onFreezeFollowState.value()
                // Re-read the center AFTER freezing: while playing, the ticker
                // advanced currentFrame between the touch-down and the slop
                // threshold, so the down-time anchor would jump the waveform back.
                val scrubAnchor = currentFrameState.value
                var accumPx = firstDrag.position.x - down.position.x
                var scrubFrame = (scrubAnchor - accumPx * framesPerPixelI).toInt()
                onScrubMoveState.value(scrubFrame)
                horizontalDrag(firstDrag.id) { ch ->
                    ch.consume()
                    accumPx += (ch.position - ch.previousPosition).x
                    scrubFrame = (scrubAnchor - accumPx * framesPerPixelI).toInt()
                    onScrubMoveState.value(scrubFrame)
                }
                onScrubEndState.value(scrubFrame)
            }
        }
    }

    Canvas(modifier = dragModifier) {
        drawRect(Color.Black)
        val widthF = size.width.coerceAtLeast(1f)
        val midY = size.height / 2f
        val scale = size.height / 2f / 32768f
        val pairCount = samples.size / 2                 // real column count (0 if not rendered yet)
        val pairs = pairCount.coerceAtLeast(1)           // guard for the division below only
        val maxX = minOf(size.width.toInt().coerceAtLeast(1), pairCount)

        // Use the SAME integer frames-per-pixel the renderer used (framesOnScreen /
        // pixel-count), and center on the playhead. renderCentered() places
        // currentFrame on the middle pixel, so this keeps markers/selection exactly
        // over the drawn audio at any window width.
        val framesPerPixelI = ((sampleRate.coerceAtLeast(1) * 10) / pairs).coerceAtLeast(1)
        fun frameToX(frame: Float): Float = widthF / 2f + (frame - currentFrame) / framesPerPixelI

        val markerIconPx = 32.dp.toPx()
        val pennantW = 20.dp.toPx()
        val pennantH = 24.dp.toPx()

        // Content is centered on `currentFrame` via frameToX; during a scrub the
        // VM updates currentFrame on every move so the waveform re-renders and
        // re-centers live (no manual canvas translate).
        run {
            // Waveform
            for (x in 0 until maxX) {
                val idx = x * 2
                val y1 = midY - (samples[idx] * scale)
                val y2 = midY - (samples[idx + 1] * scale)
                drawLine(Color.White, start = Offset(x.toFloat(), y1), end = Offset(x.toFloat(), y2))
            }

            // Baseline
            drawLine(color = Color(0xFF13C4C3), start = Offset(0f, midY), end = Offset(size.width, midY))

            // Selection fill
            val selStartFrame = selectionStartProgress
                ?.let { if (durationFrames > 0) it.coerceIn(0f, 1f) * durationFrames else null }
            val selEndFrame = selectionEndProgress
                ?.let { if (durationFrames > 0) it.coerceIn(0f, 1f) * durationFrames else null }

            if (selStartFrame != null && selEndFrame != null) {
                val left = frameToX(minOf(selStartFrame, selEndFrame))
                val right = frameToX(maxOf(selStartFrame, selEndFrame))
                if (right > left) {
                    drawRect(
                        color = Color(0x3345818E),
                        topLeft = Offset(left, 0f),
                        size = Size(right - left, size.height)
                    )
                }
            }

            // Markers — pole (full-height line) + flag (extending right from the top)
            // carrying the label. Color-coded and shaped by kind so book/chapter/verse
            // are visually distinct:
            //   VERSE   = yellow  pennant (V-notch bottom), label "5" / "1-2"
            //   CHAPTER = orange  flat banner,              label = chapter number
            //   BOOK    = magenta down-pointing tag,        label = book slug
            markerFrames.forEachIndexed { i, frame ->
                val x = frameToX(frame.toFloat())
                if (x in -widthF..widthF * 2) {
                    val kind = markerKinds.getOrNull(i) ?: MarkerKind.VERSE
                    val fillColor = when (kind) {
                        MarkerKind.VERSE -> Color(0xFFFFDD00)
                        MarkerKind.CHAPTER -> Color(0xFFFF9100)
                        MarkerKind.BOOK -> Color(0xFFE040FB)
                    }
                    val textColor = if (kind == MarkerKind.BOOK) Color.White else Color(0xFF1A1A1A)
                    val label = markerLabels.getOrNull(i).orEmpty()
                    val measured = if (label.isNotEmpty()) {
                        textMeasurer.measure(
                            label,
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = textColor,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        )
                    } else null
                    // Widen the flag to fit the label (bridges/book slugs are wider).
                    val labelPadX = 6.dp.toPx()
                    val flagW = maxOf(pennantW, (measured?.size?.width?.toFloat() ?: 0f) + labelPadX * 2)
                    val bodyBottom = pennantH * 0.72f
                    val path = Path().apply {
                        when (kind) {
                            MarkerKind.VERSE -> {
                                moveTo(x, 0f); lineTo(x + flagW, 0f)
                                lineTo(x + flagW, pennantH)
                                lineTo(x + flagW / 2f, pennantH * 0.82f)   // V-notch
                                lineTo(x, pennantH); close()
                            }
                            MarkerKind.CHAPTER -> {
                                moveTo(x, 0f); lineTo(x + flagW, 0f)       // flat banner
                                lineTo(x + flagW, pennantH)
                                lineTo(x, pennantH); close()
                            }
                            MarkerKind.BOOK -> {
                                moveTo(x, 0f); lineTo(x + flagW, 0f)       // tag w/ down tip
                                lineTo(x + flagW, bodyBottom)
                                lineTo(x + flagW / 2f, pennantH)
                                lineTo(x, bodyBottom); close()
                            }
                        }
                    }
                    drawLine(fillColor, Offset(x, 0f), Offset(x, size.height))
                    drawPath(path, color = fillColor)
                    if (measured != null) {
                        drawText(
                            measured,
                            topLeft = Offset(x + labelPadX, (bodyBottom - measured.size.height) / 2f)
                        )
                    }
                }
            }

            // Start marker — icon top-anchored, right edge at x; line runs from icon bottom to canvas bottom
            if (selStartFrame != null) {
                val x = frameToX(selStartFrame)
                if (x in -widthF..widthF * 2) {
                    withTransform({ translate(left = x - markerIconPx, top = 0f) }) {
                        with(startMarkerPainter) { draw(Size(markerIconPx, markerIconPx)) }
                    }
                    drawLine(Color(0xFF45818E), Offset(x, markerIconPx), Offset(x, size.height))
                }
            }
            // End marker — icon bottom-anchored, left edge at x; line runs from canvas top to icon top
            if (selEndFrame != null) {
                val x = frameToX(selEndFrame)
                if (x in -widthF..widthF * 2) {
                    withTransform({ translate(left = x, top = size.height - markerIconPx) }) {
                        with(endMarkerPainter) { draw(Size(markerIconPx, markerIconPx)) }
                    }
                    drawLine(Color(0xFF45818E), Offset(x, 0f), Offset(x, size.height - markerIconPx))
                }
            }
        }

        // Playback cursor — fixed at canvas center
        drawLine(
            color = Color(0xFF1EA7FD),
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Minimap widget
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MinimapWidget(
    showMinimap: Boolean,
    minimapSamples: FloatArray,
    progress: Float,
    markerFrames: List<Int>,
    durationFrames: Int,
    sampleRate: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    selectionStartProgress: Float?,
    selectionEndProgress: Float?,
    onSeek: (Float) -> Unit,
    onShowMinimap: () -> Unit,
    onShowSource: () -> Unit,
    sourceAudioState: SourceAudioPlayerController.UiState,
    onSourceTogglePlayPause: () -> Unit,
    onSourceSeek: (Float) -> Unit,
    onWidthChanged: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(TranslationRecorderTheme.veryDarkGray1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { onWidthChanged(it.width) }
        ) {
            if (showMinimap) {
                MinimapCanvas(
                    samples = minimapSamples,
                    progress = progress,
                    markerFrames = markerFrames,
                    durationFrames = durationFrames,
                    sampleRate = sampleRate,
                    textMeasurer = textMeasurer,
                    selectionStartProgress = selectionStartProgress,
                    selectionEndProgress = selectionEndProgress,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                SourceAudioPanel(
                    state = sourceAudioState,
                    onTogglePlayPause = onSourceTogglePlayPause,
                    onSeek = onSourceSeek,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(onClick = onShowMinimap, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.SkipPrevious, // placeholder; represents "overview/minimap"
                    contentDescription = stringResource(Res.string.cd_minimap),
                    tint = if (showMinimap) Color.White else Color(0xFF888888)
                )
            }
            IconButton(onClick = onShowSource, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Headset,
                    contentDescription = stringResource(Res.string.cd_source_audio),
                    tint = if (!showMinimap) Color.White else Color(0xFF888888)
                )
            }
        }
    }
}

/**
 * Inline source-audio player rendered inside the minimap toggle area when the
 * user selects the source-audio tab. Mirrors the recorder screen's source row:
 * play/pause + monospace timecode + scrubber. When unavailable, shows a muted
 * "no source audio" message.
 */
@Composable
private fun SourceAudioPanel(
    state: SourceAudioPlayerController.UiState,
    onTogglePlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.available) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.source_audio_none),
                color = TranslationRecorderTheme.gray0,
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }

    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) stringResource(Res.string.cd_pause_source) else stringResource(Res.string.cd_play_source),
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = state.elapsedText,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color.White
            )
        )
        Slider(
            value = state.progress,
            onValueChange = onSeek,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4CAF50),
                activeTrackColor = Color(0xFF4CAF50),
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
        Text(
            text = state.durationText,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color.White
            )
        )
    }
}

@Composable
private fun MinimapCanvas(
    samples: FloatArray,
    progress: Float,
    markerFrames: List<Int>,
    durationFrames: Int,
    sampleRate: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    selectionStartProgress: Float?,
    selectionEndProgress: Float?,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                if (size.width > 0) {
                    val progress = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek(progress)
                }
                waitForUpOrCancellation()?.consume()
            }
        }
    ) {
        val widthF = size.width.coerceAtLeast(1f)
        val midY = size.height / 2f
        val scale = size.height / 2f / 32768f
        val pairCount = samples.size / 2                 // real column count (0 if not rendered yet)
        val maxX = minOf(size.width.toInt().coerceAtLeast(1), pairCount)

        // Draw waveform
        for (x in 0 until maxX) {
            val idx = x * 2
            val y1 = midY - (samples[idx] * scale)
            val y2 = midY - (samples[idx + 1] * scale)
            drawLine(Color(0xFFCCCCCC), Offset(x.toFloat(), y1), Offset(x.toFloat(), y2))
        }

        // The minimap renderer bins frames with the EXACT ratio frame/totalFrames,
        // so frame f is drawn at pixel f/durationFrames * width. Position the
        // playhead and markers with that same exact ratio — the playhead then lands
        // exactly where you click (progress = x/width) with no gap, at any width.
        if (durationFrames > 0) {
            fun frameToX(frame: Float): Float = (frame / durationFrames) * widthF

            // Timecode ruler — MM:SS labels + faint gridlines at a fitted interval
            // (~50px spacing, stepping in 5s), matching the original recorder minimap.
            val durationSec = durationFrames.toDouble() / sampleRate.coerceAtLeast(1)
            if (durationSec > 0.0) {
                val pxPerSec = widthF / durationSec
                var intervalSec = 1.0
                if (pxPerSec < 50.0) {
                    intervalSec = 0.0
                    while (intervalSec * pxPerSec < 50.0) intervalSec += 5.0
                }
                val gridColor = Color(0x33FFFFFF)
                val labelStyle = TextStyle(fontSize = 9.sp, color = Color(0xFFAAAAAA))
                var t = intervalSec
                while (intervalSec > 0.0 && t < durationSec) {
                    val x = ((t / durationSec) * widthF).toFloat()
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
                    val total = t.toInt()
                    val mm = total / 60
                    val ss = total % 60
                    val label = "${if (mm < 10) "0" else ""}$mm:${if (ss < 10) "0" else ""}$ss"
                    val measured = textMeasurer.measure(label, style = labelStyle)
                    val lx = (x - measured.size.width - 2.dp.toPx()).coerceAtLeast(0f)
                    drawText(measured, topLeft = Offset(lx, 1.dp.toPx()))
                    t += intervalSec
                }
            }

            markerFrames.forEach { frame ->
                val x = frameToX(frame.toFloat())
                if (x in 0f..widthF) {
                    drawLine(Color(0xFFFFDD00), Offset(x, 0f), Offset(x, size.height))
                }
            }

            // Teal section markers
            selectionStartProgress?.let { p ->
                drawLine(Color(0xFF13C4C3), Offset(p * widthF, 0f), Offset(p * widthF, size.height))
            }
            selectionEndProgress?.let { p ->
                drawLine(Color(0xFF13C4C3), Offset(p * widthF, 0f), Offset(p * widthF, size.height))
            }

            // Blue playback position
            val posX = progress * widthF
            drawLine(Color(0xFF1EA7FD), Offset(posX, 0f), Offset(posX, size.height))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Playback Tools (transport + edit state machine)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlaybackTools(
    isPlaying: Boolean,
    elapsedText: String,
    durationText: String,
    hasStart: Boolean,
    hasEnd: Boolean,
    hasCuts: Boolean,
    canRedo: Boolean,
    canCut: Boolean,
    canSave: Boolean,
    onSeekBackward: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onMarkStart: () -> Unit,
    onMarkEnd: () -> Unit,
    onCut: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(TranslationRecorderTheme.blue)
            .padding(horizontal = 8.dp)
    ) {
        // Timestamp — left-aligned, single line
        Text(
            text = "$elapsedText / $durationText",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            modifier = Modifier.align(Alignment.CenterStart)
        )

        // Transport controls — absolutely centered
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSeekBackward, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(Res.string.cd_skip_backward), tint = Color.White)
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(Res.string.cd_play_pause),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onSeekForward, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = stringResource(Res.string.cd_skip_forward), tint = Color.White)
            }
        }

        // Edit state machine controls + save — right-aligned
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Undo/Redo — visible after cuts have been made
            if (hasCuts) {
                IconButton(onClick = onUndo, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(Res.string.edit_undo), tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            if (canRedo) {
                IconButton(onClick = onRedo, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(Res.string.edit_redo), tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            // Clear — visible when start is set but end is not yet set
            if (hasStart && !hasEnd) {
                EditIconButton(painter = painterResource(Res.drawable.ic_clear_markers), contentDescription = stringResource(Res.string.edit_clear), onClick = onClear)
            }
            // Start mark — visible when no selection is pending
            if (!hasStart) {
                EditIconButton(painter = painterResource(Res.drawable.ic_start_marker), contentDescription = stringResource(Res.string.edit_in), onClick = onMarkStart)
            }
            // End mark — visible after start is set
            if (hasStart && !hasEnd) {
                EditIconButton(painter = painterResource(Res.drawable.ic_out_marker), contentDescription = stringResource(Res.string.edit_out), onClick = onMarkEnd)
            }
            // Cut — visible when both start and end are set
            if (hasStart && hasEnd) {
                EditIconButton(painter = painterResource(Res.drawable.ic_cut), contentDescription = stringResource(Res.string.edit_cut), onClick = onCut, enabled = canCut)
            }
            // Save — always visible
            IconButton(onClick = onSave, enabled = canSave, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(Res.string.cd_save_as_new_take),
                    tint = if (canSave) Color.White else Color(0x88FFFFFF)
                )
            }
        }
    }
}

@Composable
private fun EditIconButton(
    painter: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color(0x88FFFFFF),
            modifier = Modifier.size(32.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Verse Marker Mode — counter bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MarkerCounterBar(
    versesRemaining: Int,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(TranslationRecorderTheme.veryDarkGray1)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onExit, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.cd_exit_verse_marker_mode), tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = versesRemaining.toString(),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(Res.string.playback_markers_remaining_label),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Verse Marker Mode — marker toolbar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MarkerToolbar(
    isPlaying: Boolean,
    elapsedText: String,
    durationText: String,
    onSeekBackward: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onDropMarker: () -> Unit,
    onDone: () -> Unit
) {
    // Verse-marker mode uses the original's bright-yellow toolbar (#FDD835) to
    // distinguish it from the blue playback transport; icons/text are dark for
    // contrast on yellow.
    val markerBarColor = Color(0xFFFDD835)
    val onMarkerBar = Color(0xFF1A1A1A)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(markerBarColor)
            .padding(horizontal = 8.dp)
    ) {
        // Timestamp — left-aligned, single line
        Text(
            text = "$elapsedText / $durationText",
            color = onMarkerBar,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            modifier = Modifier.align(Alignment.CenterStart)
        )

        // Transport controls — absolutely centered
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSeekBackward, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(Res.string.cd_skip_backward), tint = onMarkerBar)
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(Res.string.cd_play_pause),
                    tint = onMarkerBar,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onSeekForward, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = stringResource(Res.string.cd_skip_forward), tint = onMarkerBar)
            }
        }

        // Drop marker + done — right-aligned
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDropMarker, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(Res.string.cd_place_verse_marker), tint = onMarkerBar)
            }
            IconButton(onClick = onDone, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Check, contentDescription = stringResource(Res.string.cd_done_save_markers), tint = onMarkerBar)
            }
        }
    }
}
