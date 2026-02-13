package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
    val isRecording by viewModel.isRecording.collectAsState()
    val waveformRenderer by viewModel.waveformRenderer.collectAsState()
    val targetName by viewModel.targetName.collectAsState()
    
    // Manage render initialization based on size
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
                Text(targetName, style = MaterialTheme.typography.titleMedium)
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
            
            // Controls
            Row(
                modifier = Modifier.padding(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRecording) {
                    Button(onClick = { viewModel.stopRecording() }) {
                        Text("Stop")
                    }
                } else {
                    Button(onClick = { viewModel.startRecording() }) {
                        Text("Record")
                    }
                }
            }
        }
    }
}
