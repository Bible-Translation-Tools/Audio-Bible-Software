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
import btt_recorder2.composeapp.generated.resources.playback_markers_placed_label

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
                versesMarked = ui.versesMarked,
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
                currentFrame = ui.currentFrame,
                sampleRate = ui.sampleRate,
                selectionStartProgress = ui.selectionStartProgress,
                selectionEndProgress = ui.selectionEndProgress,
                durationFrames = ui.durationFrames,
                textMeasurer = textMeasurer,
                onSeekToFrame = viewModel::seekToFrame,
                onSelectionStartMoved = viewModel::setSelectionStartAtProgress,
                onSelectionEndMoved = viewModel::setSelectionEndAtProgress,
                onVerseMarkerDragStart = viewModel::startVerseMarkerDrag,
                onVerseMarkerDragUpdate = viewModel::updateVerseMarkerDrag,
                onVerseMarkerDragEnd = viewModel::endVerseMarkerDrag,
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
                onDone = viewModel::saveVerseMarkersAsNewTake
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
    currentFrame: Int,
    sampleRate: Int,
    selectionStartProgress: Float?,
    selectionEndProgress: Float?,
    durationFrames: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    onSeekToFrame: (Int) -> Unit = {},
    onSelectionStartMoved: (Float) -> Unit = {},
    onSelectionEndMoved: (Float) -> Unit = {},
    onVerseMarkerDragStart: (index: Int) -> Unit = {},
    onVerseMarkerDragUpdate: (progress: Float) -> Unit = {},
    onVerseMarkerDragEnd: () -> Unit = {},
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
    val onVerseMarkerDragUpdateState = rememberUpdatedState(onVerseMarkerDragUpdate)
    val onVerseMarkerDragEndState = rememberUpdatedState(onVerseMarkerDragEnd)
    val onSeekToFrameState = rememberUpdatedState(onSeekToFrame)

    var dragAccumPx by remember { mutableStateOf(0f) }

    val startMarkerPainter = painterResource(Res.drawable.ic_startmarker_cyan)
    val endMarkerPainter = painterResource(Res.drawable.ic_endmarker_cyan)

    val dragModifier = modifier.pointerInput(framesOnScreen) {
        var dragStartFrame = 0
        var dragAccumLocal = 0f
        var activeMarker: String? = null  // "start", "end", "verse:N", or null = waveform scroll

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touchX = down.position.x
            val curFrame = currentFrameState.value
            val w = size.width.toFloat().coerceAtLeast(1f)
            val leftFrame = curFrame - (framesOnScreen / 2f)

            fun fToX(frame: Float) = ((frame - leftFrame) / framesOnScreen) * w

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

            dragStartFrame = curFrame
            dragAccumLocal = 0f
            dragAccumPx = 0f

            if (activeMarker != null) {
                // Marker drag — no touch slop, respond immediately
                down.consume()

                // For verse markers: notify start once so the VM captures the
                // stable index before any re-sorting happens.
                val verseIdx = activeMarker?.removePrefix("verse:")?.toIntOrNull()
                if (verseIdx != null) onVerseMarkerDragStartState.value(verseIdx)

                while (true) {
                    val event = awaitPointerEvent()
                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                    ch.consume()
                    if (!ch.pressed) break
                    val newX = ch.position.x
                    val newFrame = leftFrame + (newX / w) * framesOnScreen
                    val progress = if (dur > 0) (newFrame / dur.toFloat()).coerceIn(0f, 1f) else 0f
                    when {
                        activeMarker == "start" -> onSelectionStartMovedState.value(progress)
                        activeMarker == "end" -> onSelectionEndMovedState.value(progress)
                        verseIdx != null -> onVerseMarkerDragUpdateState.value(progress)
                    }
                }

                if (verseIdx != null) onVerseMarkerDragEndState.value()
            } else {
                // Waveform scroll — wait for touch slop before tracking
                val firstDrag = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() }
                    ?: return@awaitEachGesture
                dragAccumLocal = firstDrag.position.x - down.position.x
                dragAccumPx = dragAccumLocal
                horizontalDrag(firstDrag.id) { ch ->
                    ch.consume()
                    dragAccumLocal += (ch.position - ch.previousPosition).x
                    dragAccumPx = dragAccumLocal
                }
                val framesPerPixel = framesOnScreen / w
                val targetFrame = (dragStartFrame - (dragAccumLocal * framesPerPixel)).toInt()
                onSeekToFrameState.value(targetFrame)
                dragAccumPx = 0f
            }
        }
    }

    Canvas(modifier = dragModifier) {
        drawRect(Color.Black)
        val widthF = size.width.coerceAtLeast(1f)
        val midY = size.height / 2f
        val scale = size.height / 2f / 32768f
        val maxX = minOf(size.width.toInt().coerceAtLeast(1), samples.size / 2)
        val leftFrame = currentFrame.toFloat() - (framesOnScreen / 2f)

        fun frameToX(frame: Float): Float = ((frame - leftFrame) / framesOnScreen) * widthF

        val markerIconPx = 32.dp.toPx()
        val pennantW = 20.dp.toPx()
        val pennantH = 24.dp.toPx()

        withTransform({ translate(left = dragAccumPx) }) {
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

            // Yellow verse markers — pole (full-height line) + flag extending right from top
            markerFrames.forEach { frame ->
                val x = frameToX(frame.toFloat())
                if (x in -widthF..widthF * 2) {
                    val pennantPath = Path().apply {
                        moveTo(x, 0f)
                        lineTo(x + pennantW, 0f)
                        lineTo(x + pennantW, pennantH)
                        lineTo(x + pennantW / 2f, pennantH * 0.82f)
                        lineTo(x, pennantH)
                        close()
                    }
                    drawLine(Color(0xFFFFDD00), Offset(x, 0f), Offset(x, size.height))
                    drawPath(pennantPath, color = Color(0xFFFFDD00))
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

        // Playback cursor — fixed at center, outside drag transform
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
                    println("MinimapCanvas tap: x=${down.position.x}, width=${size.width}, seekProgress=$progress")
                    onSeek(progress)
                }
                waitForUpOrCancellation()?.consume()
            }
        }
    ) {
        val widthF = size.width.coerceAtLeast(1f)
        val midY = size.height / 2f
        val scale = size.height / 2f / 32768f
        val maxX = minOf(size.width.toInt().coerceAtLeast(1), samples.size / 2)

        // Draw waveform
        for (x in 0 until maxX) {
            val idx = x * 2
            val y1 = midY - (samples[idx] * scale)
            val y2 = midY - (samples[idx + 1] * scale)
            drawLine(Color(0xFFCCCCCC), Offset(x.toFloat(), y1), Offset(x.toFloat(), y2))
        }

        // Yellow verse marker lines
        if (durationFrames > 0) {
            markerFrames.forEach { frame ->
                val x = (frame.toFloat() / durationFrames) * widthF
                if (x in 0f..widthF) {
                    drawLine(Color(0xFFFFDD00), Offset(x, 0f), Offset(x, size.height))
                }
            }

            // Teal section markers
            selectionStartProgress?.let { p ->
                val x = p * widthF
                drawLine(Color(0xFF13C4C3), Offset(x, 0f), Offset(x, size.height))
            }
            selectionEndProgress?.let { p ->
                val x = p * widthF
                drawLine(Color(0xFF13C4C3), Offset(x, 0f), Offset(x, size.height))
            }
        }

        // Blue playback position
        val posX = progress * widthF
        drawLine(Color(0xFF1EA7FD), Offset(posX, 0f), Offset(posX, size.height))
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
    versesMarked: Int,
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
            text = versesMarked.toString(),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(Res.string.playback_markers_placed_label),
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

        // Drop marker + done — right-aligned
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDropMarker, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(Res.string.cd_place_verse_marker), tint = Color(0xFFFFDD00))
            }
            IconButton(onClick = onDone, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Check, contentDescription = stringResource(Res.string.cd_done_save_markers), tint = Color.White)
            }
        }
    }
}
