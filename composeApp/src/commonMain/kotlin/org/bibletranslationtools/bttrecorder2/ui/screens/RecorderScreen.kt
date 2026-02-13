package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.recorder.WaveformView
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.RecorderViewModel

@Composable
fun RecorderScreen(
    viewModel: RecorderViewModel,
    onBackClick: () -> Unit
) {
    val state by viewModel.recordingState.collectAsState()
    val waveformRenderer by viewModel.waveformRenderer.collectAsState()
    val targetUi by viewModel.targetUi.collectAsState()
    val timerText by viewModel.timerText.collectAsState()

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

    Scaffold(
        topBar = {
            // Setup simple top bar or custom
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = onBackClick) {
                    Text("Back")
                }
                Spacer(modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(targetUi.title, style = MaterialTheme.typography.titleMedium)
                    Text(targetUi.subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Waveform Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .onSizeChanged { 
                        viewWidth = it.width 
                    }
            ) {
                if (waveformRenderer != null) {
                    WaveformView(
                        renderer = waveformRenderer,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("Initializing Audio...", modifier = Modifier.align(Alignment.Center))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { viewModel.goPreviousTarget() },
                    enabled = targetUi.canGoPrevious
                ) {
                    Text("Previous")
                }
                Text(timerText, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { viewModel.goNextTarget() },
                    enabled = targetUi.canGoNext
                ) {
                    Text("Next")
                }
            }

            Row(
                modifier = Modifier.padding(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (state) {
                    RecorderViewModel.RecordingUiState.Idle -> {
                        Button(onClick = { viewModel.startRecording() }) {
                            Text("Record")
                        }
                    }

                    RecorderViewModel.RecordingUiState.Recording -> {
                        Button(onClick = { viewModel.pauseRecording() }) {
                            Text("Pause")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { viewModel.stopRecording() }) {
                            Text("Stop")
                        }
                    }

                    RecorderViewModel.RecordingUiState.Paused -> {
                        Button(onClick = { viewModel.resumeRecording() }) {
                            Text("Resume")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { viewModel.stopRecording() }) {
                            Text("Stop")
                        }
                    }

                    RecorderViewModel.RecordingUiState.Review -> {
                        Button(onClick = { viewModel.saveRecording() }) {
                            Text("Save")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.cancelRecording() }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
