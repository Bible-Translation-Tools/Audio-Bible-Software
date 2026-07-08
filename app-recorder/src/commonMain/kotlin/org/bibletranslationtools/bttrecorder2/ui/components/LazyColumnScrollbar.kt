package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A [LazyColumn] with a persistent, draggable scrollbar overlay on the right edge.
 *
 * Compose Multiplatform doesn't yet ship a built-in cross-platform scrollbar
 * for Android, so we paint one ourselves. The thumb:
 *   - Stays visible the entire time the list overflows the viewport, with a
 *     subtle idle alpha and a stronger alpha while the user is actively
 *     scrolling (touch fling) or dragging the thumb.
 *   - Can be dragged vertically to scroll the underlying list proportionally,
 *     using the visible items' average height to translate a thumb-pixel delta
 *     into a list-pixel delta (so variable-height items like expanded chapter
 *     or unit cards behave correctly).
 *   - Disappears entirely when every item fits on screen.
 *
 * Touch target: the track is 16 dp wide (with a 4 dp painted thumb). The
 * pointerInput captures the whole track, so users can grab the thumb anywhere
 * in that band.
 *
 * Usage mirrors [LazyColumn]:
 * ```
 * LazyColumnWithScrollbar(modifier = Modifier.fillMaxSize()) {
 *     items(list) { ... }
 * }
 * ```
 */
@Composable
fun LazyColumnWithScrollbar(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    thumbColor: Color? = null,
    thumbWidth: Dp = 10.dp,
    trackWidth: Dp = 24.dp,
    minThumbHeight: Dp = 48.dp,
    content: LazyListScope.() -> Unit
) {
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize()
        ) {
            content()
        }
        Scrollbar(
            state = state,
            thumbColor = thumbColor,
            thumbWidth = thumbWidth,
            minThumbHeight = minThumbHeight,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(trackWidth)
        )
    }
}

@Composable
private fun Scrollbar(
    state: LazyListState,
    thumbColor: Color?,
    thumbWidth: Dp,
    minThumbHeight: Dp,
    modifier: Modifier = Modifier
) {
    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo

    // Nothing to scroll past — bail out so we don't draw a useless full-height
    // thumb or steal touches from the underlying list.
    if (totalItems == 0 || visibleItems.isEmpty() || visibleItems.size >= totalItems) return

    val baseColor = thumbColor ?: MaterialTheme.colorScheme.onSurface
    var dragging by remember { mutableStateOf(false) }

    // Stronger alpha while user is actively interacting; quieter at rest so the
    // thumb doesn't compete with row content visually.
    val targetAlpha = if (state.isScrollInProgress || dragging) 0.55f else 0.25f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 200),
        label = "scrollbarAlpha"
    )

    Box(
        modifier = modifier
            // pointerInput key is intentionally `Unit`: it must NOT change while
            // a drag is in flight, or the gesture detector restarts and the
            // active drag is cancelled mid-stroke. `visibleItems.size` flips
            // during scrolling, which is exactly what was previously cutting
            // the drag short after one or two ticks.
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                ) { change, dragAmount ->
                    change.consume()

                    // Re-read layoutInfo on every drag tick so size/avg height
                    // reflect the *current* scroll position, not whatever they
                    // were when the gesture started.
                    val info = state.layoutInfo
                    val visible = info.visibleItemsInfo
                    val total = info.totalItemsCount
                    if (visible.isEmpty() || total == 0 || visible.size >= total) {
                        return@detectVerticalDragGestures
                    }

                    // Average item height is approximated from the visible
                    // items' total pixel span — close enough for variable-
                    // height lists and avoids having to measure every offscreen
                    // item.
                    val viewportPx = size.height.toFloat()
                    val firstItem = visible.first()
                    val lastItem = visible.last()
                    val visibleSpanPx = (lastItem.offset + lastItem.size - firstItem.offset)
                        .toFloat()
                        .coerceAtLeast(1f)
                    val avgItemHeight = visibleSpanPx / visible.size

                    val thumbHeightPx = (visible.size.toFloat() / total * viewportPx)
                        .coerceAtLeast(minThumbHeight.toPx())
                    val travelPx = (viewportPx - thumbHeightPx).coerceAtLeast(1f)
                    val totalScrollablePx = (total - visible.size) * avgItemHeight
                    val scrollPerThumbPx = totalScrollablePx / travelPx
                    val listDelta = dragAmount * scrollPerThumbPx

                    // dispatchRawDelta is synchronous and lock-free, unlike
                    // scrollBy which requires the scrollable mutex. Launching
                    // one scrollBy coroutine per drag tick caused them to fight
                    // for the lock and most updates got dropped — symptom: list
                    // moves once then freezes.
                    state.dispatchRawDelta(listDelta)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val viewportHeight = size.height
            val thumbHeightPx = (visibleItems.size.toFloat() / totalItems * viewportHeight)
                .coerceAtLeast(minThumbHeight.toPx())
            val travel = (viewportHeight - thumbHeightPx).coerceAtLeast(0f)

            val scrollableItems = (totalItems - visibleItems.size).coerceAtLeast(1).toFloat()
            val scrollFraction =
                (state.firstVisibleItemIndex.toFloat() / scrollableItems).coerceIn(0f, 1f)
            val thumbTop = scrollFraction * travel

            val thumbWidthPx = thumbWidth.toPx()
            drawRect(
                color = baseColor.copy(alpha = alpha),
                topLeft = Offset(x = size.width - thumbWidthPx, y = thumbTop),
                size = Size(width = thumbWidthPx, height = thumbHeightPx)
            )
        }
    }
}
