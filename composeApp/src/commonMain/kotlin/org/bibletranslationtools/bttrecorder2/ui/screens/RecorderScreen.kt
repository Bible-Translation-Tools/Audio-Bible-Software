package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.bttrecorder2.ui.playback.SourceAudioPlayerController
import org.bibletranslationtools.bttrecorder2.ui.recorder.WaveformView
import org.bibletranslationtools.bttrecorder2.ui.theme.TranslationRecorderTheme
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.RecorderViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import btt_recorder2.composeapp.generated.resources.Res
import btt_recorder2.composeapp.generated.resources.action_back
import btt_recorder2.composeapp.generated.resources.cd_record_transport
import btt_recorder2.composeapp.generated.resources.recorder_initializing_audio
import btt_recorder2.composeapp.generated.resources.recorder_label_chapter
import btt_recorder2.composeapp.generated.resources.recorder_label_verse
import btt_recorder2.composeapp.generated.resources.recorder_saving
import btt_recorder2.composeapp.generated.resources.recorder_stop
import btt_recorder2.composeapp.generated.resources.source_audio_none
import btt_recorder2.composeapp.generated.resources.cd_pause_source
import btt_recorder2.composeapp.generated.resources.cd_play_source
import org.jetbrains.compose.resources.stringResource

@Composable
fun RecorderScreen(
    viewModel: RecorderViewModel,
    onNavigateToPlayback: (Int) -> Unit,
    onBackClick: () -> Unit
) {
    val state by viewModel.recordingState.collectAsState()
    val waveformRenderer by viewModel.waveformRenderer.collectAsState()
    val targetUi by viewModel.targetUi.collectAsState()
    val timerText by viewModel.timerText.collectAsState()
    val volumeLevel by viewModel.volumeLevel.collectAsState()
    val audioError by viewModel.audioError.collectAsState()
    val sourceAudioState by viewModel.sourceAudioState.collectAsState()

    var viewWidth by remember { mutableStateOf(0) }

    LaunchedEffect(viewWidth) {
        if (viewWidth > 0 && waveformRenderer == null) {
            viewModel.initializeAudio(viewWidth)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.savedTakeEvents.collectLatest { takeNumber ->
            onNavigateToPlayback(takeNumber)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.cleanup()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF202020))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TranslationRecorderTheme.veryDarkGray1)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(Res.string.action_back), tint = Color.White)
            }
            Text(
                "${targetUi.sourceLabel}  ${targetUi.bookLabel}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.width(12.dp))
            StepperControl(
                label = stringResource(Res.string.recorder_label_chapter),
                value = targetUi.chapterValue,
                onMinus = viewModel::goPreviousChapter,
                onPlus = viewModel::goNextChapter,
                minusEnabled = targetUi.canGoPreviousChapter,
                plusEnabled = targetUi.canGoNextChapter
            )
            Spacer(Modifier.width(12.dp))
            StepperControl(
                label = stringResource(Res.string.recorder_label_verse),
                value = targetUi.unitValue,
                onMinus = viewModel::goPreviousUnit,
                onPlus = viewModel::goNextUnit,
                minusEnabled = targetUi.canGoPreviousUnit,
                plusEnabled = targetUi.canGoNextUnit
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onSizeChanged { viewWidth = it.width }
                ) {
                    if (waveformRenderer != null) {
                        WaveformView(
                            renderer = waveformRenderer,
                            modifier = Modifier.fillMaxSize(),
                            waveColor = Color.White,
                            backgroundColor = Color.Black
                        )
                    } else {
                        Text(stringResource(Res.string.recorder_initializing_audio), color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
                VolumeMeter(
                    level = volumeLevel,
                    modifier = Modifier
                        .width(24.dp)
                        .fillMaxHeight()
                )
            }

            SourceAudioRow(
                state = sourceAudioState,
                audioError = audioError,
                onTogglePlayPause = viewModel::toggleSourcePlayback,
                onSeek = viewModel::seekSourceToProgress
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(TranslationRecorderTheme.blue)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(timerText, color = Color.White, style = MaterialTheme.typography.headlineMedium)
                RecordTransportButton(
                    state = state,
                    onRecord = viewModel::startRecording,
                    onPause = viewModel::pauseRecording,
                    onResume = viewModel::resumeRecording,
                    onStop = viewModel::stopRecording
                )

                when (state) {
                    RecorderViewModel.RecordingUiState.Idle -> {
                        Spacer(Modifier.width(120.dp))
                    }
                    RecorderViewModel.RecordingUiState.Recording -> {
                        OutlinedButton(onClick = viewModel::stopRecording) { Text(stringResource(Res.string.recorder_stop), color = Color.White) }
                    }
                    RecorderViewModel.RecordingUiState.Paused -> {
                        OutlinedButton(onClick = viewModel::stopRecording) { Text(stringResource(Res.string.recorder_stop), color = Color.White) }
                    }
                    RecorderViewModel.RecordingUiState.Review -> {
                        // Transient committing state: Stop persisted the take and we
                        // navigate to Playback for review/edit. No Save/Cancel gate.
                        Text(stringResource(Res.string.recorder_saving), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepperControl(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusEnabled: Boolean,
    plusEnabled: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onMinus, enabled = minusEnabled, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Remove,
                contentDescription = null,
                tint = if (minusEnabled) Color.White else Color.White.copy(alpha = 0.38f)
            )
        }
        Box(
            modifier = Modifier
                .height(36.dp)
                .width(44.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(value, color = Color.White)
        }
        IconButton(onClick = onPlus, enabled = plusEnabled, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = if (plusEnabled) Color.White else Color.White.copy(alpha = 0.38f)
            )
        }
    }
}

/**
 * Volume meter that matches the original BTT-Recorder design: a solid filled bar
 * grown symmetrically from a horizontal centerline (top half mirrored to bottom).
 * The bar's color steps through volumeBase → volumeLow → volumeGood → volumeHigh →
 * volumeClipped based on the peak amplitude (normalized 0..1) using the same
 * thresholds as the legacy app: -24 dB / -18 dB / -3 dB / 0 dB.
 */
@Composable
private fun VolumeMeter(level: Float, modifier: Modifier = Modifier) {
    val color = when {
        level < 0.063f -> TranslationRecorderTheme.volumeBase    // < -24 dB
        level < 0.126f -> TranslationRecorderTheme.volumeLow     // -24..-18 dB
        level < 0.708f -> TranslationRecorderTheme.volumeGood    // -18..-3 dB
        level < 1.0f   -> TranslationRecorderTheme.volumeHigh    // -3..0 dB
        else           -> TranslationRecorderTheme.volumeClipped // clipping
    }
    val baselineColor = TranslationRecorderTheme.secondary

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = h / 2f
        val halfBarHeight = (level.coerceIn(0f, 1f)) * (h / 2f)

        // Centerline (matches CanvasView's baseline draw)
        drawRect(
            color = baselineColor,
            topLeft = androidx.compose.ui.geometry.Offset(0f, center - 0.5f),
            size = androidx.compose.ui.geometry.Size(w, 1f)
        )

        if (halfBarHeight > 0f) {
            // Top half (grows up from center)
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(0f, center - halfBarHeight),
                size = androidx.compose.ui.geometry.Size(w, halfBarHeight)
            )
            // Bottom half (grows down from center)
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(0f, center),
                size = androidx.compose.ui.geometry.Size(w, halfBarHeight)
            )
        }
    }
}

@Composable
private fun RecordTransportButton(
    state: RecorderViewModel.RecordingUiState,
    onRecord: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val (icon, action) = when (state) {
        RecorderViewModel.RecordingUiState.Idle -> Icons.Default.Mic to onRecord
        RecorderViewModel.RecordingUiState.Recording -> Icons.Default.Pause to onPause
        // Resuming from pause continues RECORDING (not audio playback), so show the
        // record (mic) icon rather than a play triangle.
        RecorderViewModel.RecordingUiState.Paused -> Icons.Default.Mic to onResume
        RecorderViewModel.RecordingUiState.Review -> Icons.Default.Stop to onStop
    }

    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = action) {
            Icon(icon, contentDescription = stringResource(Res.string.cd_record_transport), tint = Color.White, modifier = Modifier.size(42.dp))
        }
    }
}

/**
 * Source-audio playback row shown above the transport controls.
 * Renders one of three states:
 *   - Available: play/pause button + scrubber + monospace elapsed/duration labels
 *   - Unavailable: muted "No source audio" message
 *   - Audio device error: appended in strong red
 *
 * The scrubber is intentionally bound to the same green play color used elsewhere
 * for play affordances; matching the existing visual language for "this is the
 * playback control".
 */
@Composable
private fun SourceAudioRow(
    state: SourceAudioPlayerController.UiState,
    audioError: String?,
    onTogglePlayPause: () -> Unit,
    onSeek: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(TranslationRecorderTheme.darkGray0)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.available) {
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) stringResource(Res.string.cd_pause_source) else stringResource(Res.string.cd_play_source),
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.elapsedText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.White
                )
            )
            Slider(
                value = state.progress,
                onValueChange = onSeek,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
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
                    fontSize = 12.sp,
                    color = Color.White
                )
            )
        } else {
            Text(
                stringResource(Res.string.source_audio_none),
                color = TranslationRecorderTheme.gray0,
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (audioError != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                audioError,
                color = TranslationRecorderTheme.strongRed,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
