package org.bibletranslationtools.bttrecorder2.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.recorder.WaveformView
import org.bibletranslationtools.bttrecorder2.ui.theme.TranslationRecorderTheme
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.RecorderViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop

@Composable
fun RecorderScreen(
    viewModel: RecorderViewModel,
    onBackClick: () -> Unit
) {
    val state by viewModel.recordingState.collectAsState()
    val waveformRenderer by viewModel.waveformRenderer.collectAsState()
    val targetUi by viewModel.targetUi.collectAsState()
    val timerText by viewModel.timerText.collectAsState()
    val volumeLevel by viewModel.volumeLevel.collectAsState()

    var viewWidth by remember { mutableStateOf(0) }

    LaunchedEffect(viewWidth) {
        if (viewWidth > 0 && waveformRenderer == null) {
            viewModel.initializeAudio(viewWidth)
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
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                "${targetUi.sourceLabel}  ${targetUi.bookLabel}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.width(12.dp))
            StepperControl(
                label = "Chapter",
                value = targetUi.chapterValue,
                onMinus = viewModel::goPreviousChapter,
                onPlus = viewModel::goNextChapter,
                minusEnabled = targetUi.canGoPreviousChapter,
                plusEnabled = targetUi.canGoNextChapter
            )
            Spacer(Modifier.width(12.dp))
            StepperControl(
                label = "Verse",
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
                        Text("Initializing Audio...", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
                VolumeMeter(
                    level = volumeLevel,
                    modifier = Modifier
                        .width(28.dp)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(TranslationRecorderTheme.darkGray0)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("No source audio", color = TranslationRecorderTheme.gray0, style = MaterialTheme.typography.titleMedium)
            }

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
                        OutlinedButton(onClick = viewModel::stopRecording) { Text("Stop", color = Color.White) }
                    }
                    RecorderViewModel.RecordingUiState.Paused -> {
                        OutlinedButton(onClick = viewModel::stopRecording) { Text("Stop", color = Color.White) }
                    }
                    RecorderViewModel.RecordingUiState.Review -> {
                        Row {
                            OutlinedButton(onClick = viewModel::saveRecording) {
                                Text("Save", color = Color.White)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = viewModel::cancelRecording) {
                                Text("Cancel", color = Color.White)
                            }
                        }
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
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onMinus, enabled = minusEnabled, modifier = Modifier.size(width = 36.dp, height = 36.dp)) {
            Text("-")
        }
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .height(36.dp)
                .width(44.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(value, color = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(onClick = onPlus, enabled = plusEnabled, modifier = Modifier.size(width = 36.dp, height = 36.dp)) {
            Text("+")
        }
    }
}

@Composable
private fun VolumeMeter(level: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val segments = 18
        val lit = (segments * level.coerceIn(0f, 1f)).toInt()
        for (i in 0 until segments) {
            val on = i < lit
            val color = when {
                i < 6 -> if (on) TranslationRecorderTheme.volumeLow else TranslationRecorderTheme.volumeBase.copy(alpha = 0.35f)
                i < 12 -> if (on) TranslationRecorderTheme.volumeGood else TranslationRecorderTheme.volumeBase.copy(alpha = 0.35f)
                i < 15 -> if (on) TranslationRecorderTheme.volumeHigh else TranslationRecorderTheme.volumeBase.copy(alpha = 0.35f)
                else -> if (on) TranslationRecorderTheme.volumeClipped else TranslationRecorderTheme.volumeBase.copy(alpha = 0.35f)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(color, RoundedCornerShape(1.dp))
            )
            Spacer(Modifier.height(2.dp))
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
        RecorderViewModel.RecordingUiState.Paused -> Icons.Default.PlayArrow to onResume
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
            Icon(icon, contentDescription = "record transport", tint = Color.White, modifier = Modifier.size(42.dp))
        }
    }
}
