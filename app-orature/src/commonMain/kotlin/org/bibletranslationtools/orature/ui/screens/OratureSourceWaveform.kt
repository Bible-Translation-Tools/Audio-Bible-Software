package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.delete
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureMarkerInfo
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val SOURCE_FRAMES_ON_SCREEN = 10 * 44100
private val WaveColor = Color(0xFF8A94A6)
private val WaveBgColor = Color(0xFFE5E8EB)
private val CursorColor = Color(0xFFD32F2F)
private const val PCM_MAX = 32768f

/**
 * The shared source-audio waveform used by both Consume and Chunking (JVM: `MarkerWaveform`): a
 * playhead-centered scrolling waveform with a live scrub, a scrollbar, and marker nodes. When
 * [editable] is false (Consume), markers are read-only labels; when true (Chunking), each marker
 * gets a drag handle (move) and a delete button. The waveform snapshot comes from the ViewModel's
 * precomputed peaks (no per-frame decode).
 */
@Composable
fun OratureSourceWaveform(
    waveformProvider: () -> FloatArray,
    positionProvider: () -> Int,
    totalFramesProvider: () -> Int,
    markers: List<OratureMarkerInfo>,
    editable: Boolean,
    onSeek: (frame: Int) -> Unit,
    onClick: () -> Unit,
    frameClock: () -> Long,
    onMoveMarker: (id: Int, newFrame: Int) -> Unit = { _, _ -> },
    onDeleteMarker: (id: Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(WaveBgColor)) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }

        // Live scrub: seek on every move (JVM setOnLayerScroll). Marker drag handles consume their own.
        Box(
            modifier = Modifier.fillMaxSize().pointerInput(widthPx) {
                var startPos = 0
                var accDx = 0f
                detectDragGestures(
                    onDragStart = { startPos = positionProvider(); accDx = 0f; onClick() },
                    onDrag = { change, delta ->
                        accDx += delta.x
                        onSeek(startPos - (accDx * SOURCE_FRAMES_ON_SCREEN / widthPx).roundToInt())
                        change.consume()
                    }
                )
            }
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            frameClock()
            val midY = size.height / 2f
            val scale = size.height / 2f / PCM_MAX
            val buffer = waveformProvider()
            if (buffer.size >= 2) {
                val columns = buffer.size / 2
                for (col in 0 until columns) {
                    val minV = buffer[col * 2]
                    val maxV = buffer[col * 2 + 1]
                    if (minV == 0f && maxV == 0f) continue
                    val x = col.toFloat() / columns * size.width
                    drawLine(WaveColor, Offset(x, midY - minV * scale), Offset(x, midY - maxV * scale), strokeWidth = 1f)
                }
            }
            drawLine(OratureColors.SurfaceTertiary, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)
            drawLine(CursorColor, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 2f)
        }

        // Marker nodes. Keyed by (id, location) so an edit (which re-sorts) recreates the node
        // fresh at the committed spot in one atomic recompose (no post-release jump).
        for (marker in markers) {
            key(marker.verseIndex, marker.location) {
                SourceMarkerNode(
                    marker = marker,
                    widthPx = widthPx,
                    editable = editable,
                    positionProvider = positionProvider,
                    frameClock = frameClock,
                    onMove = { newFrame -> onMoveMarker(marker.verseIndex, newFrame) },
                    onDelete = { onDeleteMarker(marker.verseIndex) }
                )
            }
        }
    }
}

@Composable
private fun SourceMarkerNode(
    marker: OratureMarkerInfo,
    widthPx: Float,
    editable: Boolean,
    positionProvider: () -> Int,
    frameClock: () -> Long,
    onMove: (newFrame: Int) -> Unit,
    onDelete: () -> Unit
) {
    var dragDx by remember { mutableFloatStateOf(0f) }

    // JVM MarkerNode: full-height node at the boundary — a line, a top chip (bookmark + number, plus
    // a delete button when editable), and (editable only) a drag-handle button at the bottom.
    Box(
        modifier = Modifier
            .offset {
                frameClock()
                val half = SOURCE_FRAMES_ON_SCREEN / 2
                val vpStart = positionProvider() - half
                val vpEnd = positionProvider() + half
                val span = (vpEnd - vpStart).toFloat()
                if (marker.location in vpStart..vpEnd && span > 0) {
                    val x = (marker.location - vpStart) / span * widthPx
                    IntOffset((x + dragDx).roundToInt(), 0)
                } else IntOffset(-100_000, 0)
            }
            .fillMaxHeight()
    ) {
        Box(modifier = Modifier.align(Alignment.TopStart).width(2.dp).fillMaxHeight().background(OratureColors.Primary))

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(OratureColors.Foreground)
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = OratureColors.Primary, modifier = Modifier.size(20.dp))
            Text(marker.label, color = OratureColors.RegularText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (editable) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(Res.string.delete),
                    tint = OratureColors.Primary,
                    modifier = Modifier.size(28.dp).clickable(onClick = onDelete).padding(4.dp)
                )
            }
        }

        if (editable) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = OratureColors.Primary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-14).dp, y = (-8).dp)
                    .size(28.dp)
                    .background(OratureColors.Foreground, RoundedCornerShape(4.dp))
                    .border(1.dp, OratureColors.SurfaceTertiary, RoundedCornerShape(4.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(marker.verseIndex, widthPx) {
                        detectDragGestures(
                            onDragStart = { dragDx = 0f },
                            onDrag = { change, delta -> dragDx += delta.x; change.consume() },
                            onDragEnd = {
                                onMove(marker.location + (dragDx * SOURCE_FRAMES_ON_SCREEN / widthPx).roundToInt())
                                dragDx = 0f
                            },
                            onDragCancel = { dragDx = 0f }
                        )
                    }
                    .padding(4.dp)
            )
        }
    }
}
