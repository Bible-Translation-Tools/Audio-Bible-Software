package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import org.bibletranslationtools.orature.resources.`import`
import org.bibletranslationtools.orature.resources.openIn
import org.bibletranslationtools.orature.resources.options
import org.bibletranslationtools.orature.resources.play
import org.bibletranslationtools.orature.resources.reRecord
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureMarkerInfo
import org.bibletranslationtools.shared.ui.playback.AudioTimeline
import org.bibletranslationtools.shared.ui.playback.PcmSource
import org.bibletranslationtools.shared.ui.playback.PlaybackDisplayClock
import org.bibletranslationtools.shared.ui.playback.WaveformPeakCache
import org.bibletranslationtools.shared.ui.playback.fillWindow
import kotlin.math.floor
import kotlin.math.roundToInt

// JVM WAV_COLOR_LIGHT (wave amplitude lines) / WAV_BACKGROUND_COLOR_LIGHT (white behind them),
// from common/data/ColorTheme.kt. (Dark theme is #808080 on #343434 — not wired here yet.)
private val WaveColor = Color(0xFF66768B)
private val CursorColor = Color(0xFFD32F2F)
// JVM waveform image background is white; the volume-bar strip is the dark navy below.
private val WaveformBg = Color(0xFFFFFFFF)
private val VolumeBarBg = Color(0xFF001533)
private const val PCM_MAX = 32768f

// JVM DisplayAndAudioPositionFormulas: SECONDS_ON_SCREEN(10) * 44100 sample rate.
private const val FRAMES_ON_SCREEN = 441_000
// JVM MARKER_AREA_WIDTH — the marker's grab area (drag strip) width.
private val MARKER_GRAB_WIDTH = 24.dp
// Total marker node width: the grab strip + the bottom label chip + the ⋮ menu chip. Only the grab
// strip and the menu are interactive, so the rest is transparent to the scrub-drag beneath.
private val MARKER_NODE_WIDTH = 104.dp
private const val VOLUME_BAR_WIDTH_DP = 25

/** frames→pixels for the current viewport (JVM framesToPixels). */
private fun framesToPixels(frames: Int, widthPx: Float, framesOnScreen: Int = FRAMES_ON_SCREEN): Float =
    frames.toFloat() / framesOnScreen * widthPx

/** pixels→frames for scrub/marker drag (JVM pixelsToFrames). */
private fun pixelsToFrames(pixels: Float, widthPx: Float, framesOnScreen: Int = FRAMES_ON_SCREEN): Int =
    (pixels / widthPx * framesOnScreen).roundToInt()

/**
 * The narration audio workspace (JVM: `AudioWorkspaceView`): the `AudioScene` composite of the
 * recorded chapter audio + live mic take, drawn as a scrolling waveform with the playhead near
 * center, draggable verse markers, a center-out volume bar, and a real scrollbar. Dragging the
 * waveform background scrubs (seek); dragging a marker's grab-area moves that verse boundary.
 * Everything reads the ViewModel's per-frame snapshot each display frame.
 */
@Composable
fun OratureAudioWorkspace(
    waveformProvider: () -> FloatArray,
    viewportsProvider: () -> List<IntRange>,
    splitPivotProvider: () -> Int?,
    markerInfos: List<OratureMarkerInfo>,
    volumeProvider: () -> Float,
    positionProvider: () -> Int,
    totalFramesProvider: () -> Int,
    // Frame-stable PLAYBACK renderer (shared engine). When [isRecordingView] is false the waveform is
    // drawn from the peak cache via fillWindow at the smooth [clock] position; when true it falls
    // back to the live AudioScene [waveformProvider] buffer above.
    timelineProvider: () -> AudioTimeline?,
    peakCacheFor: (PcmSource) -> WaveformPeakCache?,
    clock: PlaybackDisplayClock,
    waveformSampleRate: Int,
    isRecordingView: () -> Boolean,
    scrollEnabled: Boolean,
    markersEditable: Boolean,
    onSeekToFrame: (Int) -> Unit,
    onMarkerDragStart: (verseIndex: Int) -> Unit,
    onMarkerDragEnd: (verseIndex: Int, deltaFrames: Int) -> Unit,
    onPlayVerse: (verseIndex: Int) -> Unit,
    onRecordAgain: (verseIndex: Int) -> Unit,
    onEditVerse: (verseIndex: Int) -> Unit = {},
    onImportVerse: (verseIndex: Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var frameTick by remember { mutableLongStateOf(0L) }
    // Advance the display clock every frame (it no-ops unless playing) AND keep frameTick moving for
    // the recording view.
    LaunchedEffect(Unit) { while (true) withFrameNanos { frameTick = it; clock.onFrame(it) } }
    // A lambda that reads frameTick (a snapshot state); calling it inside a layout/offset block
    // subscribes that block to per-frame updates so markers reposition as the waveform scrolls.
    val frameClock: () -> Long = { frameTick }

    Column(modifier = modifier.fillMaxSize().background(WaveformBg)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            WaveformArea(
                waveformProvider = waveformProvider,
                viewportsProvider = viewportsProvider,
                splitPivotProvider = splitPivotProvider,
                markerInfos = markerInfos,
                positionProvider = positionProvider,
                timelineProvider = timelineProvider,
                peakCacheFor = peakCacheFor,
                clock = clock,
                waveformSampleRate = waveformSampleRate,
                isRecordingView = isRecordingView,
                scrollEnabled = scrollEnabled,
                markersEditable = markersEditable,
                onSeekToFrame = onSeekToFrame,
                onMarkerDragStart = onMarkerDragStart,
                onMarkerDragEnd = onMarkerDragEnd,
                onPlayVerse = onPlayVerse,
                onRecordAgain = onRecordAgain,
                onEditVerse = onEditVerse,
                onImportVerse = onImportVerse,
                frameClock = frameClock,
                frameTick = frameTick,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            VolumeBar(volumeProvider = volumeProvider, frameTick = frameTick)
        }
        WaveformScrollbar(
            positionProvider = positionProvider,
            totalFramesProvider = totalFramesProvider,
            enabled = scrollEnabled,
            onSeekToFrame = onSeekToFrame,
            frameClock = frameClock
        )
    }
}

/** The single marker's on-screen x (px) for the current viewports, or null if off-screen. */
private fun markerScreenX(
    marker: OratureMarkerInfo,
    viewports: List<IntRange>,
    pivot: Int?,
    widthPx: Float
): Float? {
    val n = viewports.size
    if (n == 0 || widthPx <= 0f) return null
    val segWidth = widthPx / n
    for (vp in viewports.indices) {
        val viewport = viewports[vp]
        if (pivot != null && n > 1) {
            if (vp != viewports.lastIndex && marker.verseIndex > pivot) continue
            if (vp == viewports.lastIndex && marker.verseIndex <= pivot) continue
        }
        val span = viewport.last - viewport.first
        if (span > 0 && marker.location in viewport) {
            return segWidth * vp + (marker.location - viewport.first).toFloat() / span * segWidth
        }
    }
    return null
}

@Composable
private fun WaveformArea(
    waveformProvider: () -> FloatArray,
    viewportsProvider: () -> List<IntRange>,
    splitPivotProvider: () -> Int?,
    markerInfos: List<OratureMarkerInfo>,
    positionProvider: () -> Int,
    timelineProvider: () -> AudioTimeline?,
    peakCacheFor: (PcmSource) -> WaveformPeakCache?,
    clock: PlaybackDisplayClock,
    waveformSampleRate: Int,
    isRecordingView: () -> Boolean,
    scrollEnabled: Boolean,
    markersEditable: Boolean,
    onSeekToFrame: (Int) -> Unit,
    onMarkerDragStart: (verseIndex: Int) -> Unit,
    onMarkerDragEnd: (verseIndex: Int, deltaFrames: Int) -> Unit,
    onPlayVerse: (verseIndex: Int) -> Unit,
    onRecordAgain: (verseIndex: Int) -> Unit,
    onEditVerse: (verseIndex: Int) -> Unit,
    onImportVerse: (verseIndex: Int) -> Unit,
    frameClock: () -> Long,
    frameTick: Long,
    modifier: Modifier
) {
    BoxWithConstraints(modifier = modifier.background(WaveformBg)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val framesOnScreen = (waveformSampleRate * 10).coerceAtLeast(1)

        // Per-pixel min/max scratch for the frame-stable playback path (reused across frames).
        val colCount = widthPx.toInt().coerceAtLeast(1) + 1
        val colMins = remember(colCount) { FloatArray(colCount) }
        val colMaxs = remember(colCount) { FloatArray(colCount) }

        // Markers ride the SMOOTH clock during playback (single viewport recentered on the clock);
        // during recording they use the scene's own (possibly split) viewports.
        val markerViewports: () -> List<IntRange> = {
            if (isRecordingView()) viewportsProvider()
            else {
                val half = framesOnScreen / 2
                val c = clock.displayFrame.toInt()
                listOf((c - half) until (c + half))
            }
        }

        // Scrub: dragging the background seeks (JVM setOnLayerScroll). Cache the position at drag
        // start; each move seeks by the accumulated pixel delta (drag left → forward).
        val scrubModifier = if (scrollEnabled) {
            Modifier.pointerInput(Unit) {
                var startPos = 0
                var accDx = 0f
                detectDragGestures(
                    onDragStart = {
                        startPos = if (isRecordingView()) positionProvider() else clock.displayFrame.toInt()
                        accDx = 0f
                    },
                    onDrag = { change, delta ->
                        accDx += delta.x
                        onSeekToFrame(startPos - pixelsToFrames(accDx, size.width.toFloat()))
                        change.consume()
                    }
                )
            }
        } else Modifier

        Canvas(modifier = Modifier.fillMaxSize().then(scrubModifier)) {
            val midY = size.height / 2f
            val scale = size.height / 2f / PCM_MAX
            val tl = timelineProvider()

            if (!isRecordingView() && tl != null && tl.totalFrames > 0) {
                // FRAME-STABLE PLAYBACK: sample the peak cache on the absolute frame grid at the
                // smooth clock position (identical approach to the translation surfaces). Columns
                // never re-bin as the window scrolls, so the wave glides instead of crawling.
                clock.displayFrame // subscribe: redraw each frame while playing
                val widthF = size.width.coerceAtLeast(1f)
                val fppD = framesOnScreen.toDouble() / widthF
                val leftFrame = clock.displayFrame.toDouble() - framesOnScreen / 2.0
                val firstCol = floor(leftFrame / fppD).toLong()
                val xShift = kotlin.math.round((firstCol * fppD - leftFrame) / fppD).toFloat() + 0.5f
                tl.fillWindow(firstCol, colCount, fppD, peakCacheFor, colMins, colMaxs)
                for (i in 0 until colCount) {
                    val mn = colMins[i]
                    if (mn.isNaN()) continue
                    val x = i + xShift
                    drawLine(WaveColor, Offset(x, midY - colMaxs[i] * scale), Offset(x, midY - mn * scale))
                }
            } else {
                // RECORDING (or cache not ready): the live AudioScene composite, pixel-driven so a
                // low-res buffer never leaves gaps. @Suppress reads frameTick to redraw each frame.
                @Suppress("UNUSED_EXPRESSION") frameTick
                val buffer = waveformProvider()
                val columns = buffer.size / 2
                if (columns > 0) {
                    val w = size.width.toInt().coerceAtLeast(1)
                    for (x in 0 until w) {
                        val col = (x.toLong() * columns / w).toInt().coerceIn(0, columns - 1)
                        val minV = buffer[col * 2]
                        val maxV = buffer[col * 2 + 1]
                        if (minV == 0f && maxV == 0f) continue
                        val px = x + 0.5f
                        drawLine(WaveColor, Offset(px, midY - minV * scale), Offset(px, midY - maxV * scale))
                    }
                }
            }
            drawLine(OratureColors.SurfaceTertiary, Offset(0f, midY), Offset(size.width, midY))
            // Playhead: the current position sits at the viewport center in every mode.
            val cursorX = size.width / 2f
            drawLine(CursorColor, Offset(cursorX, 0f), Offset(cursorX, size.height))
        }

        // Verse markers overlaid as interactive nodes, each repositioned per frame as the wave scrolls.
        for (marker in markerInfos) {
            VerseMarker(
                marker = marker,
                widthPx = widthPx,
                viewportsProvider = markerViewports,
                splitPivotProvider = splitPivotProvider,
                editable = markersEditable,
                frameClock = frameClock,
                onDragStart = { onMarkerDragStart(marker.verseIndex) },
                onDragEnd = { deltaFrames -> onMarkerDragEnd(marker.verseIndex, deltaFrames) },
                onPlay = { onPlayVerse(marker.verseIndex) },
                onRecordAgain = { onRecordAgain(marker.verseIndex) },
                onEditVerse = { onEditVerse(marker.verseIndex) },
                onImportVerse = { onImportVerse(marker.verseIndex) }
            )
        }
    }
}

/**
 * A verse marker (JVM: `VerseMarkerControl`): a 2px primary line with a bookmark + verse-number
 * label at top and a ⋮ menu. The 24dp grab area drags the marker (movable ones only). Positioned
 * by an offset lambda that re-reads the frame clock each frame so it tracks the scrolling wave.
 */
@Composable
private fun VerseMarker(
    marker: OratureMarkerInfo,
    widthPx: Float,
    viewportsProvider: () -> List<IntRange>,
    splitPivotProvider: () -> Int?,
    editable: Boolean,
    frameClock: () -> Long,
    onDragStart: () -> Unit,
    onDragEnd: (deltaFrames: Int) -> Unit,
    onPlay: () -> Unit,
    onRecordAgain: () -> Unit,
    onEditVerse: () -> Unit,
    onImportVerse: () -> Unit
) {
    val density = LocalDensity.current
    val grabHalfPx = with(density) { (MARKER_GRAB_WIDTH / 2).toPx() }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var menuOpen by remember { mutableStateOf(false) }
    val canDrag = editable && marker.movable

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight = canDrag && hovered
    val chipBorder = OratureColors.Primary.copy(alpha = 0.30f)
    // JVM hover: the drag-area strip fills with -wa-primary-light-80 and the bookmark goes solid.
    val dragStripBg = if (highlight) OratureColors.Primary.copy(alpha = 0.15f) else Color.Transparent

    // The node is wide enough to hold the line + label + ⋮ menu; only the drag strip and the menu
    // are interactive, so the rest lets the scrub-drag through. The LINE is anchored at the marker x
    // (the node's left edge sits half a grab-width left of it), chips bottom-aligned (JVM layout).
    val grabHalfDp = MARKER_GRAB_WIDTH / 2
    Box(
        modifier = Modifier
            .offset {
                frameClock() // subscribe to per-frame updates
                val baseX = markerScreenX(marker, viewportsProvider(), splitPivotProvider(), widthPx)
                if (baseX == null) IntOffset(-100_000, 0) // off-screen when not in a viewport
                else IntOffset((baseX + dragDx - grabHalfPx).roundToInt(), 0)
            }
            .width(MARKER_NODE_WIDTH)
            .fillMaxHeight()
    ) {
        // Hover strip behind the line (the draggable area), full height.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(MARKER_GRAB_WIDTH)
                .fillMaxHeight()
                .background(dragStripBg)
        )
        // Vertical marker line, full height, centered in the grab strip so it sits at the marker x.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = grabHalfDp - 1.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(OratureColors.Primary)
        )

        // Bottom chip group: [bookmark + verse number] and a [⋮] menu button (JVM bottom-left).
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .background(OratureColors.Foreground, RoundedCornerShape(4.dp))
                    .border(1.dp, chipBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (highlight) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    tint = OratureColors.Primary,
                    modifier = Modifier.width(18.dp)
                )
                Text(
                    text = marker.label,
                    color = OratureColors.Primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
            // ⋮ menu (JVM VerseMenu: Play / Re-Record).
            Box {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(Res.string.options),
                    tint = OratureColors.Primary,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .background(OratureColors.Foreground, RoundedCornerShape(4.dp))
                        .border(1.dp, chipBorder, RoundedCornerShape(4.dp))
                        .clickable { menuOpen = true }
                        .padding(2.dp)
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    // JVM VerseMenu: Play / Record Again / Open In / Import (in that order).
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        text = { Text(stringResource(Res.string.play)) },
                        enabled = marker.isPlayEnabled,
                        onClick = { menuOpen = false; onPlay() }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.FiberManualRecord, contentDescription = null) },
                        text = { Text(stringResource(Res.string.reRecord)) },
                        enabled = marker.isRecordAgainEnabled,
                        onClick = { menuOpen = false; onRecordAgain() }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                        text = { Text(stringResource(Res.string.openIn)) },
                        enabled = marker.isEditEnabled,
                        onClick = { menuOpen = false; onEditVerse() }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        text = { Text(stringResource(Res.string.`import`)) },
                        enabled = marker.isEditEnabled,
                        onClick = { menuOpen = false; onImportVerse() }
                    )
                }
            }
        }

        // Drag grab area over the line (movable markers only): hover highlights + hand cursor.
        if (canDrag) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(MARKER_GRAB_WIDTH)
                    .fillMaxHeight()
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(marker.verseIndex, widthPx) {
                        detectDragGestures(
                            onDragStart = { dragDx = 0f; onDragStart() },
                            onDrag = { change, delta -> dragDx += delta.x; change.consume() },
                            onDragEnd = {
                                onDragEnd(pixelsToFrames(dragDx, widthPx))
                                dragDx = 0f
                            },
                            onDragCancel = { dragDx = 0f }
                        )
                    }
            )
        }
    }
}

/**
 * JVM `VolumeBar`: a level meter expanding OUT FROM THE CENTER with the live mic level, colored
 * blue → teal → green → yellow → red (clipping) as it gets louder, on a dark navy strip.
 */
@Composable
private fun VolumeBar(volumeProvider: () -> Float, frameTick: Long) {
    Canvas(
        modifier = Modifier
            .width(VOLUME_BAR_WIDTH_DP.dp)
            .fillMaxHeight()
            .background(VolumeBarBg)
    ) {
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

/**
 * A real horizontal scrollbar (JVM `ScrollBar` bottom of the borderpane): the thumb spans the
 * visible window over the total, positioned by the playhead; dragging it seeks. Disabled while
 * recording/playing.
 */
@Composable
private fun WaveformScrollbar(
    positionProvider: () -> Int,
    totalFramesProvider: () -> Int,
    enabled: Boolean,
    onSeekToFrame: (Int) -> Unit,
    frameClock: () -> Long
) {
    val trackColor = OratureColors.SurfaceSecondary
    val thumbColor = Color(0xFFB3B9C2)

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(14.dp).background(trackColor)
    ) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxWidth.toPx() }

        // Thumb spans the on-screen window over the total (full width when it all fits). total
        // changes rarely (only while recording, when the bar is disabled), so read it here.
        val total0 = totalFramesProvider().coerceAtLeast(1)
        val thumbFraction = (FRAMES_ON_SCREEN.toFloat() / total0).coerceIn(0.08f, 1f)
        val thumbWidthPx = trackPx * thumbFraction
        val thumbWidthDp = with(density) { thumbWidthPx.toDp() }
        // The full 0..total position range maps to this pixel travel — the scale for dragging.
        val travelPx = (trackPx - thumbWidthPx).coerceAtLeast(1f)

        val dragModifier = if (enabled) {
            Modifier.pointerInput(Unit) {
                detectDragGestures { change, delta ->
                    val total = totalFramesProvider().coerceAtLeast(1)
                    onSeekToFrame(positionProvider() + (delta.x / travelPx * total).roundToInt())
                    change.consume()
                }
            }
        } else Modifier

        Box(
            modifier = Modifier
                .offset {
                    frameClock() // follow the playhead each frame
                    val total = totalFramesProvider().coerceAtLeast(1)
                    val pos = positionProvider().coerceIn(0, total)
                    val off = (pos.toFloat() / total * travelPx).coerceIn(0f, travelPx)
                    IntOffset(off.roundToInt(), 0)
                }
                .width(thumbWidthDp)
                .fillMaxHeight()
                .alpha(if (enabled) 1f else 0.5f)
                .background(thumbColor)
                .then(dragModifier)
        )
    }
}
