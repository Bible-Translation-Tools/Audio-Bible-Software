package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowLeft
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// JVM NodeUtil.SCROLL_INCREMENT_UNIT — frames the end-arrow buttons seek per click.
const val SCROLL_INCREMENT_UNIT = 10_000
// JVM control.css .scroll-bar (track #66768B / arrows+grip #33445C) with track and thumb flipped:
// light track + arrows, dark thumb + light grip.
private val ScrollBarTrack = Color(0xFFB3B9C2)
private val ScrollBarArrow = Color(0xFF66768B)
private val ScrollBarThumb = Color(0xFF66768B)
// JVM DisplayAndAudioPositionFormulas: SECONDS_ON_SCREEN(10) * 44100 sample rate.
const val WAVEFORM_FRAMES_ON_SCREEN = 441_000

/**
 * The Orature audio scrollbar (JVM `ScrollBar` + `customizeScrollbarSkin()`): a decrement arrow, a
 * thumb sized to the on-screen window over the total and positioned by the playhead (drag to seek),
 * and an increment arrow. Each arrow seeks by [SCROLL_INCREMENT_UNIT] frames. Shared by the narration
 * workspace and the translation read-only surfaces so they render identically.
 */
@Composable
fun OratureWaveformScrollbar(
    positionProvider: () -> Int,
    totalFramesProvider: () -> Int,
    onSeekToFrame: (Int) -> Unit,
    frameClock: () -> Long,
    enabled: Boolean = true,
    framesOnScreen: Int = WAVEFORM_FRAMES_ON_SCREEN
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(20.dp).background(ScrollBarTrack),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScrollArrow(pointsLeft = true, enabled = enabled) {
            onSeekToFrame((positionProvider() - SCROLL_INCREMENT_UNIT).coerceAtLeast(0))
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val density = LocalDensity.current
            val trackPx = with(density) { maxWidth.toPx() }

            // Thumb spans the on-screen window over the total (full width when it all fits).
            val total0 = totalFramesProvider().coerceAtLeast(1)
            val thumbFraction = (framesOnScreen.toFloat() / total0).coerceIn(0.08f, 1f)
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
                    .padding(vertical = 3.dp)
                    .alpha(if (enabled) 1f else 0.5f)
                    .background(ScrollBarThumb)
                    .then(dragModifier),
                contentAlignment = Alignment.Center
            ) {
                // JVM thumb grip: DRAG_INDICATOR dots (rotated 90° for the horizontal bar).
                Icon(
                    imageVector = Icons.Filled.DragIndicator,
                    contentDescription = null,
                    tint = ScrollBarTrack, // light grip on the dark thumb
                    modifier = Modifier.height(14.dp).rotate(90f)
                )
            }
        }
        ScrollArrow(pointsLeft = false, enabled = enabled) {
            onSeekToFrame(positionProvider() + SCROLL_INCREMENT_UNIT)
        }
    }
}

/** A scrollbar end-arrow button (JVM increment/decrement-button): a solid triangle that seeks the
 *  playhead by one increment. */
@Composable
private fun ScrollArrow(pointsLeft: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(20.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (pointsLeft) Icons.Filled.ArrowLeft else Icons.Filled.ArrowRight,
            contentDescription = null,
            tint = ScrollBarArrow
        )
    }
}
