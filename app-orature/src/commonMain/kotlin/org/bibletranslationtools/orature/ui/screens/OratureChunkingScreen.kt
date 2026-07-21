package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.addChunk
import org.bibletranslationtools.orature.resources.delete
import org.bibletranslationtools.orature.resources.nextChunk
import org.bibletranslationtools.orature.resources.previousChunk
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureChunkingViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureMarkerInfo
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val CHUNK_FRAMES_ON_SCREEN = 10 * 44100
private val WaveColor = Color(0xFF8A94A6)
private val WaveBgColor = Color(0xFFE5E8EB)
private val CursorColor = Color(0xFFD32F2F)
private const val PCM_MAX = 32768f
private val MARKER_GRAB = 24.dp

/**
 * The Chunking step body (JVM: `Chunking`): the source waveform with editable chunk-boundary
 * markers (add at the playhead, drag to move, delete), plus media controls. The header undo/redo
 * (wired through the translation VM) drive this step's marker undo/redo.
 */
@Composable
fun OratureChunkingScreen(viewModel: OratureChunkingViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // JVM: `.translation-view { -fx-background-color: -wa-foreground; }` (white, not the app's
    // light-gray page background).
    Box(modifier = Modifier.fillMaxSize().background(OratureColors.Foreground), contentAlignment = Alignment.Center) {
        when {
            uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
            uiState.error != null -> Text(uiState.error!!, color = OratureColors.RegularText)
            else -> ChunkingBody(viewModel, uiState.isPlaying, uiState.addDisabled, uiState.markers)
        }
    }
}

@Composable
private fun ChunkingBody(
    viewModel: OratureChunkingViewModel,
    isPlaying: Boolean,
    addDisabled: Boolean,
    markers: List<OratureMarkerInfo>
) {
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frameTick = it } }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            OratureSourceWaveform(
                timelineProvider = viewModel::currentTimeline,
                peakCacheFor = viewModel::peakCacheFor,
                clock = viewModel.clock,
                sampleRate = viewModel.waveformSampleRate(),
                totalFramesProvider = viewModel::currentTotalFrames,
                markers = markers,
                editable = true,
                onSeek = viewModel::seekToFrame,
                onClick = viewModel::pause,
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
        // Bottom bar: Add Chunk + media controls (JVM: consume__bottom).
        Row(
            modifier = Modifier.fillMaxWidth().background(OratureColors.Foreground).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = viewModel::placeMarker,
                enabled = !addDisabled,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(Res.string.addChunk), modifier = Modifier.padding(start = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::seekPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(Res.string.previousChunk), tint = OratureColors.RegularText)
            }
            IconButton(onClick = viewModel::togglePlay) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = OratureColors.RegularText)
            }
            IconButton(onClick = viewModel::seekNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(Res.string.nextChunk), tint = OratureColors.RegularText)
            }
        }
    }
}
