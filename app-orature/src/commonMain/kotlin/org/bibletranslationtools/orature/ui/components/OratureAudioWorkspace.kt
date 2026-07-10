package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureMarkerInfo

private val WaveColor = Color(0xFF8A94A6)
private val CursorColor = Color(0xFFD32F2F)
private const val PCM_MAX = 32768f

/**
 * The narration audio workspace (JVM: `AudioWorkspaceView`): the `AudioScene` composite of the
 * recorded chapter audio + live mic take, drawn as a scrolling waveform with the playhead near
 * center, verse markers positioned against the scene viewport(s), a center-out volume bar, and a
 * scrollbar. During re-record/prepend the scene returns TWO viewports (a split view); the canvas
 * is split in half and markers are assigned to the half matching their side of the split pivot
 * (JVM: adjustMarkers). Everything reads the ViewModel's double-buffered snapshot each frame.
 */
@Composable
fun OratureAudioWorkspace(
    waveformProvider: () -> FloatArray,
    viewportsProvider: () -> List<IntRange>,
    splitPivotProvider: () -> Int?,
    markerInfos: List<OratureMarkerInfo>,
    volumeProvider: () -> Float,
    modifier: Modifier = Modifier
) {
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frameTick = it } }

    Column(modifier = modifier.fillMaxSize().background(OratureColors.Foreground)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            WaveformArea(
                waveformProvider = waveformProvider,
                viewportsProvider = viewportsProvider,
                splitPivotProvider = splitPivotProvider,
                markerInfos = markerInfos,
                frameTick = frameTick,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            VolumeBar(volumeProvider = volumeProvider, frameTick = frameTick)
        }
        HorizontalWaveformScrollbar()
    }
}

@Composable
private fun WaveformArea(
    waveformProvider: () -> FloatArray,
    viewportsProvider: () -> List<IntRange>,
    splitPivotProvider: () -> Int?,
    markerInfos: List<OratureMarkerInfo>,
    frameTick: Long,
    modifier: Modifier
) {
    Canvas(modifier = modifier.background(OratureColors.Foreground)) {
        @Suppress("UNUSED_EXPRESSION") frameTick // read to redraw each frame
        val midY = size.height / 2f
        val scale = size.height / 2f / PCM_MAX

        // The composited waveform snapshot: one min/max pair per column.
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

        // Center baseline.
        drawLine(OratureColors.SurfaceTertiary, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)

        // Verse markers (JVM: adjustMarkers). Split the canvas into one segment per viewport;
        // in the split (re-record) case, a marker goes in the segment matching its side of the pivot.
        val viewports = viewportsProvider()
        val pivot = splitPivotProvider()
        val n = viewports.size
        if (n > 0) {
            val segWidth = size.width / n
            for (info in markerInfos) {
                for (vp in viewports.indices) {
                    val viewport = viewports[vp]
                    if (pivot != null && n > 1) {
                        if (vp != viewports.lastIndex && info.verseIndex > pivot) continue
                        if (vp == viewports.lastIndex && info.verseIndex <= pivot) continue
                    }
                    val span = viewport.last - viewport.first
                    if (span > 0 && info.location in viewport) {
                        val x = segWidth * vp + (info.location - viewport.first).toFloat() / span * segWidth
                        drawLine(OratureColors.Primary.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        break
                    }
                }
            }
            // Playhead: the location is the viewport center in the normal case, and the right
            // edge of the first (left) viewport in the split case — both land at the canvas center.
            val cursorX = size.width / 2f
            drawLine(CursorColor, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 2f)
        }
    }
}

/**
 * JVM `VolumeBar`: a level meter expanding OUT FROM THE CENTER with the live mic level, colored
 * blue → teal → green → yellow → red (clipping) as it gets louder.
 */
@Composable
private fun VolumeBar(volumeProvider: () -> Float, frameTick: Long) {
    Canvas(modifier = Modifier.width(16.dp).fillMaxHeight().background(OratureColors.SurfaceSecondary)) {
        @Suppress("UNUSED_EXPRESSION") frameTick
        val level = volumeProvider().coerceIn(0f, 1f)
        if (level <= 0f) return@Canvas
        val half = size.height / 2f
        val fillHalf = half * level
        val color = when {
            level < 0.063f -> Color(0xFF085394)
            level < 0.126f -> Color(0xFF45818E)
            level < 0.708f -> Color(0xFF93C47D)
            level < 1.0f -> Color(0xFFFFE599)
            else -> Color(0xFFCF2A27)
        }
        drawRect(color = color, topLeft = Offset(0f, half - fillHalf), size = Size(size.width, fillHalf * 2f))
    }
}

@Composable
private fun HorizontalWaveformScrollbar() {
    Box(modifier = Modifier.fillMaxWidth().height(14.dp).background(OratureColors.SurfaceSecondary)) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFFB3B9C2)))
    }
}
