package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.chunk
import org.bibletranslationtools.orature.resources.confirm
import org.bibletranslationtools.orature.resources.pause
import org.bibletranslationtools.orature.resources.play
import org.bibletranslationtools.orature.resources.playSource
import org.bibletranslationtools.orature.resources.record
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OraturePeerEditViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * The Peer Edit step body (JVM: `PeerEdit`): the chunk's source audio player on top, its selected
 * target take as a playback waveform in the center, and a Play / Confirm / Record transport. Confirm
 * advances the take's checking status; Record replaces the take. While recording, the live recording
 * section replaces the playback view.
 */
@Composable
fun OraturePeerEditScreen(viewModel: OraturePeerEditViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(OratureColors.Background), contentAlignment = Alignment.Center) {
        when {
            uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
            !uiState.hasChunk || uiState.noTake -> Text(stringResource(Res.string.chunk), color = OratureColors.RegularText)
            uiState.error != null -> Text(uiState.error!!, color = OratureColors.RegularText)
            uiState.recording -> RecordingSection(
                waveformProvider = viewModel::currentRecordingWaveform,
                isActive = uiState.recordingActive,
                onToggle = viewModel::toggleRecording,
                onSave = viewModel::saveRecording,
                onCancel = viewModel::cancelRecording
            )
            else -> PeerEditBody(viewModel, uiState)
        }
    }
}

@Composable
private fun PeerEditBody(
    viewModel: OraturePeerEditViewModel,
    uiState: org.bibletranslationtools.orature.ui.viewmodels.OraturePeerEditUiState
) {
    var frameTick by remember { mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) withFrameNanos { frameTick = it } }

    Column(modifier = Modifier.fillMaxSize()) {
        // Source audio for the chunk (JVM: simpleaudioplayer top).
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "${stringResource(Res.string.chunk)} ${uiState.chunkTitle}",
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = OratureColors.RegularText
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::toggleSource) {
                Icon(if (uiState.isSourcePlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                Text(
                    if (uiState.isSourcePlaying) stringResource(Res.string.pause) else stringResource(Res.string.playSource),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        // Target take waveform (read-only, playhead-centered).
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            OratureSourceWaveform(
                waveformProvider = viewModel::currentWaveform,
                positionProvider = viewModel::currentPosition,
                totalFramesProvider = viewModel::currentTotalFrames,
                markers = emptyList(),
                editable = false,
                onSeek = viewModel::seekToFrame,
                onClick = viewModel::pause,
                frameClock = { frameTick },
                modifier = Modifier.fillMaxSize()
            )
        }
        WaveformScrollbarReadOnly(
            positionProvider = viewModel::currentPosition,
            totalFramesProvider = viewModel::currentTotalFrames,
            onSeekToFrame = viewModel::seekToFrame,
            frameClock = { frameTick }
        )

        // Transport: Play/Pause, Confirm (hidden while playing, disabled once confirmed), Record.
        Row(
            modifier = Modifier.fillMaxWidth().background(OratureColors.Foreground).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = viewModel::togglePlay,
                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
            ) {
                Icon(if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                Text(
                    if (uiState.isPlaying) stringResource(Res.string.pause) else stringResource(Res.string.play),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            if (!uiState.isPlaying) {
                OutlinedButton(onClick = viewModel::confirmChunk, enabled = !uiState.confirmed) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(Res.string.confirm), modifier = Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = viewModel::onRecordNew, enabled = !uiState.isPlaying) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(Res.string.record), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
