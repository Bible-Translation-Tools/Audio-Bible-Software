package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import org.bibletranslationtools.orature.resources.addVerseMarker
import org.bibletranslationtools.orature.resources.cancel
import org.bibletranslationtools.orature.resources.nextChunk
import org.bibletranslationtools.orature.resources.pause
import org.bibletranslationtools.orature.resources.play
import org.bibletranslationtools.orature.resources.previousChunk
import org.bibletranslationtools.orature.resources.redo
import org.bibletranslationtools.orature.resources.save
import org.bibletranslationtools.orature.resources.undo
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureVerseMarkerUiState
import org.bibletranslationtools.orature.ui.viewmodels.OratureVerseMarkerViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * The built-in Verse Marker editor (JVM: `MarkerView`): a split view with the source text on the
 * left (current verse highlighted) and, on the right, the chapter take as a scrolling waveform with
 * editable verse markers plus a transport (prev / add-marker / next / play). A header shows the
 * book+chapter, undo/redo, and Cancel / Save. Save writes the cues back to the take and closes.
 */
@Composable
fun OratureVerseMarkerScreen(
    viewModel: OratureVerseMarkerViewModel,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(OratureColors.Background)) {
        MarkerHeader(
            uiState = uiState,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onCancel = { viewModel.cancel(onClose) },
            onSave = { viewModel.saveAndClose(onClose) }
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
                !uiState.hasContent -> Text(stringResource(Res.string.addVerseMarker), color = OratureColors.RegularText)
                uiState.error != null -> Text(uiState.error!!, color = OratureColors.RegularText)
                else -> MarkerBody(viewModel, uiState)
            }
        }
    }
}

@Composable
private fun MarkerHeader(
    uiState: OratureVerseMarkerUiState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(OratureColors.Foreground).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(uiState.actionTitle, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = OratureColors.RegularText)
            if (uiState.contentTitle.isNotEmpty()) {
                Text(uiState.contentTitle, fontSize = 14.sp, color = OratureColors.RegularText)
            }
        }
        Text(
            "${uiState.placedCount}/${uiState.totalCount}",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = OratureColors.RegularText,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        IconButton(onClick = onUndo, enabled = uiState.canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(Res.string.undo), tint = OratureColors.RegularText)
        }
        IconButton(onClick = onRedo, enabled = uiState.canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(Res.string.redo), tint = OratureColors.RegularText)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onCancel, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text(stringResource(Res.string.cancel)) }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSave,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
        ) {
            Text(stringResource(Res.string.save))
        }
    }
}

@Composable
private fun MarkerBody(
    viewModel: OratureVerseMarkerViewModel,
    uiState: OratureVerseMarkerUiState
) {
    var frameTick by remember { mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) withFrameNanos { frameTick = it } }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left: source text with the current verse highlighted (JVM: SourceTextFragment).
        SourceTextPanel(
            uiState = uiState,
            modifier = Modifier.fillMaxHeight().width(320.dp).background(OratureColors.Foreground)
        )

        // Right: waveform + scrollbar + transport.
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                OratureSourceWaveform(
                    waveformProvider = viewModel::currentWaveform,
                    positionProvider = viewModel::currentPosition,
                    totalFramesProvider = viewModel::currentTotalFrames,
                    markers = uiState.markers,
                    editable = true,
                    onSeek = viewModel::seekToFrame,
                    onClick = viewModel::pause,
                    frameClock = { frameTick },
                    onMoveMarker = viewModel::moveMarker,
                    onDeleteMarker = viewModel::deleteMarker
                )
            }
            WaveformScrollbarReadOnly(
                positionProvider = viewModel::currentPosition,
                totalFramesProvider = viewModel::currentTotalFrames,
                onSeekToFrame = viewModel::seekToFrame,
                frameClock = { frameTick }
            )

            Row(
                modifier = Modifier.fillMaxWidth().background(OratureColors.Foreground).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = viewModel::placeMarker,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(Res.string.addVerseMarker), modifier = Modifier.padding(start = 6.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = viewModel::seekPrevious) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(Res.string.previousChunk), tint = OratureColors.RegularText)
                }
                IconButton(onClick = viewModel::togglePlay) {
                    Icon(
                        if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (uiState.isPlaying) stringResource(Res.string.pause) else stringResource(Res.string.play),
                        tint = OratureColors.RegularText
                    )
                }
                IconButton(onClick = viewModel::seekNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(Res.string.nextChunk), tint = OratureColors.RegularText)
                }
            }
        }
    }
}

@Composable
private fun SourceTextPanel(
    uiState: OratureVerseMarkerUiState,
    modifier: Modifier = Modifier
) {
    if (uiState.sourceText.isEmpty()) {
        Column(modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState())) { }
        return
    }
    // Follow the playhead: scroll the highlighted verse into view as it changes (JVM: the source-text
    // panel tracks highlightedChunkNumberProperty).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(uiState.highlightedIndex) {
        val target = uiState.sourceText.indexOfFirst { it.index == uiState.highlightedIndex }
        if (target >= 0) runCatching { listState.animateScrollToItem(target) }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(uiState.sourceText, key = { it.index }) { verse ->
            val highlighted = verse.index == uiState.highlightedIndex
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (highlighted) OratureColors.Primary.copy(alpha = 0.15f) else OratureColors.Background,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(
                    verse.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = OratureColors.Primary
                )
                if (verse.text.isNotEmpty()) {
                    Text(verse.text, fontSize = 15.sp, color = OratureColors.RegularText)
                }
            }
        }
    }
}
