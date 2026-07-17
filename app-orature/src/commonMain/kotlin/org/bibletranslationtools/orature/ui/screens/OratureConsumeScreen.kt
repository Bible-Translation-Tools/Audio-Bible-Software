package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.audioNotAvailable
import org.bibletranslationtools.orature.resources.pause
import org.bibletranslationtools.orature.resources.playSource
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureConsumeViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureMarkerInfo
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val CONSUME_FRAMES_ON_SCREEN = 10 * 44100
private val WaveColor = Color(0xFF8A94A6)
private val WaveBgColor = Color(0xFFE5E8EB)
private val CursorColor = Color(0xFFD32F2F)
private const val PCM_MAX = 32768f

/**
 * The Consume step body: a read-only, playhead-centered waveform of the chapter's SOURCE audio
 * with verse markers, a scrollbar, and a Play/Pause control (JVM: `Consume` + `MarkerWaveform`,
 * here through our live renderer). Dragging the waveform scrubs; markers are not editable.
 */
@Composable
fun OratureConsumeScreen(viewModel: OratureConsumeViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // JVM: `.translation-view { -fx-background-color: -wa-foreground; }` (white, not the app's
    // light-gray page background).
    Box(modifier = Modifier.fillMaxSize().background(OratureColors.Foreground), contentAlignment = Alignment.Center) {
        when {
            uiState.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
            uiState.sourceMissing -> Text(
                text = stringResource(Res.string.audioNotAvailable),
                fontSize = 18.sp,
                color = OratureColors.RegularText
            )
            uiState.error != null -> Text(uiState.error!!, color = OratureColors.RegularText)
            else -> ConsumeBody(viewModel, uiState.isPlaying)
        }
    }
}

@Composable
private fun ConsumeBody(viewModel: OratureConsumeViewModel, isPlaying: Boolean) {
    var frameTick by remember { mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) withFrameNanos { frameTick = it } }

    Column(modifier = Modifier.fillMaxSize()) {
        // Waveform area — shared source waveform, read-only markers (JVM MarkerWaveform canMove/Delete=false).
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            OratureSourceWaveform(
                waveformProvider = viewModel::currentWaveform,
                positionProvider = viewModel::currentPosition,
                totalFramesProvider = viewModel::currentTotalFrames,
                markers = viewModel.currentMarkers(),
                editable = false,
                onSeek = viewModel::seekToFrame,
                onClick = viewModel::pause,
                frameClock = { frameTick },
                modifier = Modifier.fillMaxSize()
            )
        }
        // Scrollbar.
        WaveformScrollbarReadOnly(
            positionProvider = viewModel::currentPosition,
            totalFramesProvider = viewModel::currentTotalFrames,
            onSeekToFrame = viewModel::seekToFrame,
            frameClock = { frameTick }
        )
        // Transport: Play Source / Pause.
        Row(
            modifier = Modifier.fillMaxWidth().background(OratureColors.Foreground).padding(12.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = viewModel::togglePlay,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Text(
                    text = if (isPlaying) stringResource(Res.string.pause) else stringResource(Res.string.playSource),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

/** Shared read-only audio scrollbar (used by Consume + Chunking). */
@Composable
internal fun WaveformScrollbarReadOnly(
    positionProvider: () -> Int,
    totalFramesProvider: () -> Int,
    onSeekToFrame: (Int) -> Unit,
    frameClock: () -> Long
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(14.dp).background(OratureColors.SurfaceSecondary)) {
        val trackPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        val total0 = totalFramesProvider().coerceAtLeast(1)
        val thumbFraction = (CONSUME_FRAMES_ON_SCREEN.toFloat() / total0).coerceIn(0.08f, 1f)
        val thumbWidthPx = trackPx * thumbFraction
        val thumbWidthDp = with(androidx.compose.ui.platform.LocalDensity.current) { thumbWidthPx.toDp() }
        val travelPx = (trackPx - thumbWidthPx).coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .offset {
                    frameClock()
                    val total = totalFramesProvider().coerceAtLeast(1)
                    val pos = positionProvider().coerceIn(0, total)
                    androidx.compose.ui.unit.IntOffset((pos.toFloat() / total * travelPx).roundToInt(), 0)
                }
                .width(thumbWidthDp)
                .fillMaxHeight()
                .background(Color(0xFFB3B9C2))
                .pointerInput(Unit) {
                    detectDragGestures { change, delta ->
                        val total = totalFramesProvider().coerceAtLeast(1)
                        onSeekToFrame(positionProvider() + (delta.x / travelPx * total).roundToInt())
                        change.consume()
                    }
                }
        )
    }
}
