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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.delete
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureMarkerInfo
import org.bibletranslationtools.shared.ui.playback.AudioTimeline
import org.bibletranslationtools.shared.ui.playback.PcmSource
import org.bibletranslationtools.shared.ui.playback.WaveformPeakCache
import org.bibletranslationtools.shared.ui.playback.PlaybackDisplayClock
import org.bibletranslationtools.shared.ui.playback.fillWindow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor
import kotlin.math.roundToInt

private const val SECONDS_ON_SCREEN = 10
// Wave line + background are theme-aware (OratureColors.WaveformLine / WaveformBackground, from JVM
// common/data/ColorTheme.kt: #66768B on #FFFFFF light, #808080 on #343434 dark).
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
    timelineProvider: () -> AudioTimeline?,
    peakCacheFor: (PcmSource) -> WaveformPeakCache?,
    clock: PlaybackDisplayClock,
    sampleRate: Int,
    totalFramesProvider: () -> Int,
    markers: List<OratureMarkerInfo>,
    editable: Boolean,
    onSeek: (frame: Int) -> Unit,
    onClick: () -> Unit,
    onMoveMarker: (id: Int, newFrame: Int) -> Unit = { _, _ -> },
    onDeleteMarker: (id: Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Frames spanned by the visible window (JVM: SECONDS_ON_SCREEN * sampleRate). Drives both the
    // waveform zoom and the frame↔pixel math for the scrub + marker positioning.
    val framesOnScreen = (sampleRate * SECONDS_ON_SCREEN).coerceAtLeast(1)

    // Advance the display clock once per display frame (matching the recorder's PlaybackScreen). The
    // clock interpolates the playhead at the sample rate and slew-corrects toward the real player
    // position, so the waveform scrolls smoothly at the full refresh rate instead of jumping in the
    // ~30 fps steps of the VM's position ticker (the source of the stutter/shimmer).
    LaunchedEffect(clock) {
        while (isActive) withFrameNanos { clock.onFrame(it) }
    }

    // Audio time flows left→right regardless of UI language — keep this surface LTR so markers don't
    // mirror under RTL languages (the Canvas draws in absolute coords already).
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(OratureColors.WaveformBackground)) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }

        // Per-pixel min/max scratch, reused across frames (reallocated only on resize) — this is
        // what keeps the per-frame fill allocation-free, matching the recorder's playback renderer.
        val colCount = widthPx.toInt().coerceAtLeast(1) + 1
        val colMins = remember(colCount) { FloatArray(colCount) }
        val colMaxs = remember(colCount) { FloatArray(colCount) }

        // Live scrub: seek on every move (JVM setOnLayerScroll). Marker drag handles consume their own.
        Box(
            modifier = Modifier.fillMaxSize().pointerInput(widthPx) {
                var startPos = 0
                var accDx = 0f
                detectDragGestures(
                    onDragStart = { startPos = clock.displayFrame.toInt(); accDx = 0f; onClick() },
                    onDrag = { change, delta ->
                        accDx += delta.x
                        onSeek(startPos - (accDx * framesOnScreen / widthPx).roundToInt())
                        change.consume()
                    }
                )
            }
        )

        // Pixel-driven waveform: one crisp 1px line per screen pixel, sampled from the shared
        // WaveformPeakCache via AudioTimeline.fillWindow — no gaps regardless of window width, no
        // per-tick allocation. Reading clock.displayFrame (snapshot state) here both positions the
        // window AND invalidates the draw each frame while playing — draw-only, no recomposition.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val midY = size.height / 2f
            val scale = size.height / 2f / PCM_MAX
            val widthF = size.width.coerceAtLeast(1f)
            val tl = timelineProvider()
            if (tl != null && tl.totalFrames > 0) {
                // Column k covers absolute frames [floor(k*fpp), floor((k+1)*fpp)); the playhead
                // sits at viewport center. xShift snaps the window to whole pixels (+0.5 = crisp
                // hairline centers) — the recorder's approach for flicker-free scrolling.
                val fppD = framesOnScreen.toDouble() / widthF
                val leftFrame = clock.displayFrame.toDouble() - framesOnScreen / 2.0
                val firstCol = floor(leftFrame / fppD).toLong()
                val xShift = kotlin.math.round((firstCol * fppD - leftFrame) / fppD).toFloat() + 0.5f
                tl.fillWindow(firstCol, colCount, fppD, peakCacheFor, colMins, colMaxs)
                for (i in 0 until colCount) {
                    val mn = colMins[i]
                    if (mn.isNaN()) continue
                    val x = i + xShift
                    // Hairline (default strokeWidth 0f) — always exactly 1 physical pixel, crisp on
                    // any density, matching the recorder's waveform draw.
                    drawLine(OratureColors.WaveformLine, Offset(x, midY - colMaxs[i] * scale), Offset(x, midY - mn * scale))
                }
            }
            drawLine(OratureColors.SurfaceTertiary, Offset(0f, midY), Offset(size.width, midY))
            drawLine(CursorColor, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height))
        }

        // Marker nodes. Keyed by (id, location) so an edit (which re-sorts) recreates the node
        // fresh at the committed spot in one atomic recompose (no post-release jump).
        for (marker in markers) {
            key(marker.verseIndex, marker.location) {
                SourceMarkerNode(
                    marker = marker,
                    widthPx = widthPx,
                    framesOnScreen = framesOnScreen,
                    editable = editable,
                    clock = clock,
                    onMove = { newFrame -> onMoveMarker(marker.verseIndex, newFrame) },
                    onDelete = { onDeleteMarker(marker.verseIndex) }
                )
            }
        }
    }
    }
}

@Composable
private fun SourceMarkerNode(
    marker: OratureMarkerInfo,
    widthPx: Float,
    framesOnScreen: Int,
    editable: Boolean,
    clock: PlaybackDisplayClock,
    onMove: (newFrame: Int) -> Unit,
    onDelete: () -> Unit
) {
    var dragDx by remember { mutableFloatStateOf(0f) }

    // JVM MarkerNode: full-height node at the boundary — a line, a top chip (bookmark + number, plus
    // a delete button when editable), and (editable only) a drag-handle button at the bottom.
    Box(
        modifier = Modifier
            .offset {
                // Reading clock.displayFrame (snapshot) repositions the marker each frame as the
                // waveform scrolls, in lockstep with the smooth playhead.
                val pos = clock.displayFrame.toInt()
                val half = framesOnScreen / 2
                val vpStart = pos - half
                val vpEnd = pos + half
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
                                onMove(marker.location + (dragDx * framesOnScreen / widthPx).roundToInt())
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
