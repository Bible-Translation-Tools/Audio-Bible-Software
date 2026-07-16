package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.available_takes
import org.bibletranslationtools.orature.resources.best_take
import org.bibletranslationtools.orature.resources.cancel
import org.bibletranslationtools.orature.resources.chunk
import org.bibletranslationtools.orature.resources.delete
import org.bibletranslationtools.orature.resources.edit
import org.bibletranslationtools.orature.resources.new_recording
import org.bibletranslationtools.orature.resources.pause
import org.bibletranslationtools.orature.resources.playSource
import org.bibletranslationtools.orature.resources.resume
import org.bibletranslationtools.orature.resources.save
import org.bibletranslationtools.orature.resources.take
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureBlindDraftViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureTakeCard
import org.jetbrains.compose.resources.stringResource

/**
 * The Blind Draft step body (JVM: `BlindDraft`): plays the active chunk's source audio, lists its
 * target takes (best + available), and offers a new recording. Select a take to make it "best".
 */
@Composable
fun OratureBlindDraftScreen(viewModel: OratureBlindDraftViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(OratureColors.Background), contentAlignment = Alignment.Center) {
        when {
            uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
            !uiState.hasChunk -> Text(stringResource(Res.string.chunk), color = OratureColors.RegularText)
            uiState.error != null -> Text(uiState.error!!, color = OratureColors.RegularText)
            uiState.recording -> RecordingSection(
                waveformProvider = viewModel::currentRecordingWaveform,
                isActive = uiState.recordingActive,
                onToggle = viewModel::toggleRecording,
                onSave = viewModel::saveRecording,
                onCancel = viewModel::cancelRecording
            )
            else -> Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Source audio for the chunk.
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

                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.best_take), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = OratureColors.NoteText)
                Spacer(Modifier.height(4.dp))
                uiState.selectedTake?.let { t ->
                    TakeRow(
                        t,
                        isPlaying = uiState.playingTakeId == t.id,
                        onPlay = { viewModel.toggleTake(t.id) },
                        onSelect = null,
                        onDelete = { viewModel.deleteTake(t.id) },
                        onEdit = if (uiState.canEditExternally) ({ viewModel.editTakeExternally(t.id) }) else null
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.available_takes), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = OratureColors.NoteText)
                Spacer(Modifier.height(4.dp))
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    for (t in uiState.availableTakes) {
                        TakeRow(
                            t,
                            isPlaying = uiState.playingTakeId == t.id,
                            onPlay = { viewModel.toggleTake(t.id) },
                            onSelect = { viewModel.selectTake(t.id) },
                            onDelete = { viewModel.deleteTake(t.id) },
                            onEdit = if (uiState.canEditExternally) ({ viewModel.editTakeExternally(t.id) }) else null
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                Button(
                    onClick = viewModel::onRecordNew,
                    colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(Res.string.new_recording), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/**
 * The active-recording section (JVM: `RecordingSection`): a live mic waveform with pause/resume,
 * save, and cancel. The waveform is the `ActiveRecordingRenderer` min/max buffer, drawn each frame.
 */
@Composable
internal fun RecordingSection(
    waveformProvider: () -> FloatArray,
    isActive: Boolean,
    onToggle: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    var frameTick by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameNanos { frameTick = it }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFFE5E8EB))
        ) {
            frameTick // redraw each frame
            val midY = size.height / 2f
            val scale = size.height / 2f / 32768f
            val buffer = waveformProvider()
            val columns = buffer.size / 2
            for (col in 0 until columns) {
                val x = col.toFloat() / columns * size.width
                drawLine(
                    androidx.compose.ui.graphics.Color(0xFFD32F2F),
                    androidx.compose.ui.geometry.Offset(x, midY - buffer[col * 2] * scale),
                    androidx.compose.ui.geometry.Offset(x, midY - buffer[col * 2 + 1] * scale),
                    strokeWidth = 1f
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(OratureColors.Foreground).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onToggle, colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)) {
                Icon(if (isActive) Icons.Filled.Pause else Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    if (isActive) stringResource(Res.string.pause) else stringResource(Res.string.resume),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            if (!isActive) {
                OutlinedButton(onClick = onSave) { Text(stringResource(Res.string.save)) }
            }
            Spacer(Modifier.weight(1f))
            if (!isActive) {
                OutlinedButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
            }
        }
    }
}

@Composable
private fun TakeRow(
    take: OratureTakeCard,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onSelect: (() -> Unit)?,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OratureColors.Foreground, RoundedCornerShape(6.dp))
            .border(
                1.dp,
                if (take.selected) OratureColors.Primary else OratureColors.SurfaceTertiary,
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = OratureColors.Primary,
            modifier = Modifier.size(28.dp).clickable(onClick = onPlay).padding(2.dp)
        )
        Text(
            "${stringResource(Res.string.take)} ${take.number}",
            color = OratureColors.RegularText,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        if (onSelect != null) {
            OutlinedButton(onClick = onSelect) { Text(stringResource(Res.string.best_take)) }
        } else if (take.selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = OratureColors.StatusComplete)
        }
        onEdit?.let {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(Res.string.edit),
                tint = OratureColors.Primary,
                modifier = Modifier.size(28.dp).clickable(onClick = it).padding(4.dp)
            )
        }
        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(Res.string.delete),
            tint = OratureColors.NoteText,
            modifier = Modifier.size(28.dp).clickable(onClick = onDelete).padding(4.dp)
        )
    }
}
