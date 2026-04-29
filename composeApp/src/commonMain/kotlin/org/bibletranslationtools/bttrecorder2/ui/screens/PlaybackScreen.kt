package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
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

@Composable
fun PlaybackScreen(
    viewModel: PlaybackViewModel,
    onBackClick: () -> Unit,
    onNavigateToRecorder: (sourceId: Int, targetId: Int, chapterNumber: Int, unitNumber: Int) -> Unit = { _, _, _, _ -> }
) {
    val ui by viewModel.uiState.collectAsState()
    var waveformWidth by remember { mutableStateOf(0) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(waveformWidth) {
        if (waveformWidth > 0) viewModel.setWaveformWidth(waveformWidth)
    }

    LaunchedEffect(viewModel) {
        viewModel.editedTakeSavedEvents.collectLatest { takeNumber ->
            saveMessage = "Saved as take $takeNumber"
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is PlaybackViewModel.NavEvent.Rerecord ->
                    onNavigateToRecorder(event.sourceId, event.targetId, event.chapterNumber, event.unitNumber)
                is PlaybackViewModel.NavEvent.Insert ->
                    onNavigateToRecorder(event.sourceId, event.targetId, event.chapterNumber, event.unitNumber)
            }
        }
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
                currentTakeLabel = ui.currentTakeLabel,
                onBackClick = onBackClick,
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
                modifier = Modifier.fillMaxSize()
            )
            if (saveMessage != null && ui.error == null) {
                Text(
                    text = saveMessage ?: "",
                    color = Color(0xFF6CF4C5),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
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
                canSave = ui.hasEdits,
                onSeekBackward = viewModel::seekBackward,
                onPlayPause = viewModel::togglePlayPause,
                onSeekForward = viewModel::seekForward,
                onMarkStart = viewModel::markSelectionStartAtCurrent,
                onMarkEnd = viewModel::markSelectionEndAtCurrent,
                onCut = viewModel::cutSelection,
                onClear = viewModel::clearSelection,
                onUndo = viewModel::undoEdit,
                onSave = viewModel::saveCurrentEditsAsNewTake
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
        IconButton(onClick = onBackClick, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
        }
        if (targetUi.chapterValue.isNotEmpty()) {
            Text(
                text = "ch. ${targetUi.chapterValue}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            Spacer(Modifier.width(8.dp))
        }
        if (targetUi.unitValue.isNotEmpty() && targetUi.unitValue != "0") {
            Text(
                text = "v. ${targetUi.unitValue}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
        }

        Spacer(Modifier.weight(1f))

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
            Icon(Icons.Default.BookmarkBorder, contentDescription = "Verse marker mode", tint = Color.White)
        }
        IconButton(onClick = onRerecord, enabled = hasTake, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "Re-record", tint = Color.White)
        }
        IconButton(onClick = onInsert, enabled = hasTake, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Mic, contentDescription = "Insert recording", tint = Color.White)
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
    modifier: Modifier = Modifier
) {
    // framesOnScreen is used both for drawing and for the drag-to-seek conversion.
    val framesOnScreen = (sampleRate.coerceAtLeast(1) * 10).toFloat()

    // rememberUpdatedState lets the pointerInput coroutine always read the latest currentFrame
    // even though pointerInput only reinitializes when framesOnScreen changes.
    val currentFrameState = rememberUpdatedState(currentFrame)

    // Accumulates pixel delta during drag for visual-only canvas translation.
    // The actual seek fires once on drag end to the absolute target frame.
    var dragAccumPx by remember { mutableStateOf(0f) }

    val dragModifier = modifier.pointerInput(framesOnScreen) {
        // dragStartFrame captured at the moment the drag begins — not subject to
        // playback-ticker updates that would shift the target during a slow drag.
        var dragStartFrame = 0
        detectHorizontalDragGestures(
            onDragStart = {
                dragStartFrame = currentFrameState.value
                dragAccumPx = 0f
            },
            onDragEnd = {
                val framesPerPixel = framesOnScreen / size.width.toFloat().coerceAtLeast(1f)
                // Seek to where the visual showed: dragStartFrame shifted by drag pixels.
                // Dragging right (positive px) = shift toward earlier audio = negative frames.
                val targetFrame = (dragStartFrame - (dragAccumPx * framesPerPixel)).toInt()
                onSeekToFrame(targetFrame)
                dragAccumPx = 0f
            },
            onDragCancel = { dragAccumPx = 0f },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                dragAccumPx += dragAmount
            }
        )
    }

    Canvas(modifier = dragModifier) {
        drawRect(Color.Black)
        val widthF = size.width.coerceAtLeast(1f)
        val midY = size.height / 2f
        val scale = size.height / 2f / 32768f
        val maxX = minOf(size.width.toInt().coerceAtLeast(1), samples.size / 2)
        val leftFrame = currentFrame.toFloat() - (framesOnScreen / 2f)

        fun frameToX(frame: Float): Float {
            return ((frame - leftFrame) / framesOnScreen) * widthF
        }

        // Shift waveform content during drag — visual-only, no seek until drag ends
        withTransform({ translate(left = dragAccumPx) }) {
            // Waveform
            for (x in 0 until maxX) {
                val idx = x * 2
                val y1 = midY - (samples[idx] * scale)
                val y2 = midY - (samples[idx + 1] * scale)
                drawLine(Color.White, start = Offset(x.toFloat(), y1), end = Offset(x.toFloat(), y2))
            }

            // Baseline
            drawLine(
                color = Color(0xFF13C4C3),
                start = Offset(0f, midY),
                end = Offset(size.width, midY)
            )

            // Teal section selection markers and fill
            val selStartFrame = selectionStartProgress
                ?.let { if (durationFrames > 0) it.coerceIn(0f, 1f) * durationFrames else null }
            val selEndFrame = selectionEndProgress
                ?.let { if (durationFrames > 0) it.coerceIn(0f, 1f) * durationFrames else null }

            if (selStartFrame != null) {
                val x = frameToX(selStartFrame)
                if (x in -widthF..widthF * 2) {
                    drawLine(Color(0xFF13C4C3), Offset(x, 0f), Offset(x, size.height))
                }
            }
            if (selEndFrame != null) {
                val x = frameToX(selEndFrame)
                if (x in -widthF..widthF * 2) {
                    drawLine(Color(0xFF13C4C3), Offset(x, 0f), Offset(x, size.height))
                }
            }
            if (selStartFrame != null && selEndFrame != null) {
                val left = frameToX(minOf(selStartFrame, selEndFrame))
                val right = frameToX(maxOf(selStartFrame, selEndFrame))
                if (right > left) {
                    drawRect(
                        color = Color(0x3313C4C3),
                        topLeft = Offset(left, 0f),
                        size = Size(right - left, size.height)
                    )
                }
            }

            // Yellow verse markers with labels
            val labelStyle = TextStyle(fontSize = 10.sp, color = Color(0xFFFFDD00))
            markerFrames.forEachIndexed { idx, frame ->
                val x = frameToX(frame.toFloat())
                if (x in -widthF..widthF * 2) {
                    drawLine(Color(0xFFFFDD00), Offset(x, 0f), Offset(x, size.height))
                    val label = markerLabels.getOrNull(idx) ?: ""
                    if (label.isNotEmpty()) {
                        val measured = textMeasurer.measure(label, style = labelStyle)
                        drawText(measured, topLeft = Offset((x + 3f).coerceAtMost(widthF - measured.size.width), 4f))
                    }
                }
            }
        }

        // Blue playback cursor stays fixed at center — outside the drag transform
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
                    contentDescription = "Minimap",
                    tint = if (showMinimap) Color.White else Color(0xFF888888)
                )
            }
            IconButton(onClick = onShowSource, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Headset,
                    contentDescription = "Source audio",
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
                text = "No source audio",
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
                contentDescription = if (state.isPlaying) "Pause source" else "Play source",
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
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(TranslationRecorderTheme.blue)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Time display
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = elapsedText,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            )
            Text(
                text = durationText,
                color = Color(0xFFCCCCCC),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Transport controls
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSeekBackward, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Skip backward", tint = Color.White)
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onSeekForward, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Skip forward", tint = Color.White)
            }
        }

        Spacer(Modifier.weight(1f))

        // Edit state machine controls
        // Undo — visible after any cut has been made
        if (hasCuts) {
            EditToolButton(label = "UNDO", onClick = onUndo)
        }
        // Clear — visible when start is set but end is not yet set
        if (hasStart && !hasEnd) {
            EditToolButton(label = "CLEAR", onClick = onClear)
        }
        // Start mark — visible when no selection is pending
        if (!hasStart) {
            EditToolButton(label = "[ IN", onClick = onMarkStart)
        }
        // End mark — visible after start is set
        if (hasStart && !hasEnd) {
            EditToolButton(label = "OUT ]", onClick = onMarkEnd)
        }
        // Cut — visible when both start and end are set
        if (hasStart && hasEnd) {
            EditToolButton(label = "CUT", onClick = onCut, enabled = canCut)
        }
        // Save — always visible
        IconButton(onClick = onSave, enabled = canSave, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Save as new take",
                tint = if (canSave) Color.White else Color(0x88FFFFFF)
            )
        }
    }
}

@Composable
private fun EditToolButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(40.dp)
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color(0x88FFFFFF),
            style = MaterialTheme.typography.labelSmall
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
            Icon(Icons.Default.Close, contentDescription = "Exit verse marker mode", tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = versesMarked.toString(),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "verse markers placed",
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(TranslationRecorderTheme.blue)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = elapsedText,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            )
            Text(
                text = durationText,
                color = Color(0xFFCCCCCC),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSeekBackward, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Skip backward", tint = Color.White)
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onSeekForward, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Skip forward", tint = Color.White)
            }
        }

        Spacer(Modifier.weight(1f))

        // Drop verse marker
        IconButton(onClick = onDropMarker, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Bookmark, contentDescription = "Place verse marker", tint = Color(0xFFFFDD00))
        }
        // Done
        IconButton(onClick = onDone, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Check, contentDescription = "Done — save markers", tint = Color.White)
        }
    }
}
