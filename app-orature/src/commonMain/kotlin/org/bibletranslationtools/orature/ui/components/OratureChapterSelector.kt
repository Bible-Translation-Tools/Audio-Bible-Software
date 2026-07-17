package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureChapterGridItem

private const val GRID_COLUMNS = 5

// JVM: `.btn { -fx-pref-height: 48px; }` — the prev/title/next segments all share this height.
private val CONTROL_HEIGHT = 48.dp
private val CONTROL_BORDER = 2.dp

/**
 * Orature's chapter selector (JVM: `ChapterSelector` + `ChapterSelectorPopup`/`ChapterGrid`): a
 * single connected segmented control — previous / title / next — where the title button opens a
 * 5-column grid popup of chapter numbers. The selected chapter is highlighted; completed chapters
 * show a check. The three segments are packed edge-to-edge with matching border color/width so
 * they read as one continuous pill (JVM: `chapter-selector__btn-prev/next` round only their outer
 * corners; `chapter-selector__title` borders only its top/bottom, letting the neighboring
 * segments' full borders form the shared seams).
 */
@Composable
fun OratureChapterSelector(
    chapterTitle: String,
    hasPrevious: Boolean,
    hasNext: Boolean,
    chapters: List<OratureChapterGridItem>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var gridOpen by remember { mutableStateOf(false) }
    val outerRadius = CONTROL_HEIGHT / 2

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .height(CONTROL_HEIGHT)
                .widthIn(min = CONTROL_HEIGHT)
                .border(
                    CONTROL_BORDER,
                    OratureColors.SurfaceTertiary,
                    RoundedCornerShape(topStart = outerRadius, bottomStart = outerRadius)
                )
                .clickable(enabled = hasPrevious, onClick = onPrevious),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = null,
                tint = if (hasPrevious) OratureColors.RegularText else OratureColors.RegularText.copy(alpha = 0.3f),
                modifier = Modifier.size(28.dp)
            )
        }

        Box {
            // JVM: `.chapter-selector__title { -fx-border-width: 2 0 2 0; }` — top/bottom border
            // only, drawn directly (not `HorizontalDivider`, which defaults to filling the whole
            // row's width and would stretch this segment across the entire header).
            Row(
                modifier = Modifier
                    .height(CONTROL_HEIGHT)
                    .background(OratureColors.Foreground)
                    .drawBehind {
                        val strokeWidth = CONTROL_BORDER.toPx()
                        drawLine(
                            OratureColors.SurfaceTertiary,
                            Offset(0f, strokeWidth / 2),
                            Offset(size.width, strokeWidth / 2),
                            strokeWidth
                        )
                        drawLine(
                            OratureColors.SurfaceTertiary,
                            Offset(0f, size.height - strokeWidth / 2),
                            Offset(size.width, size.height - strokeWidth / 2),
                            strokeWidth
                        )
                    }
                    .clickable(enabled = chapters.isNotEmpty()) { gridOpen = true }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = OratureColors.Primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(text = chapterTitle, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = OratureColors.RegularText)
            }

            DropdownMenu(
                expanded = gridOpen,
                onDismissRequest = { gridOpen = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                ChapterGrid(
                    chapters = chapters,
                    onSelect = {
                        gridOpen = false
                        onSelectChapter(it)
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .height(CONTROL_HEIGHT)
                .widthIn(min = CONTROL_HEIGHT)
                .border(
                    CONTROL_BORDER,
                    OratureColors.SurfaceTertiary,
                    RoundedCornerShape(topEnd = outerRadius, bottomEnd = outerRadius)
                )
                .clickable(enabled = hasNext, onClick = onNext),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = if (hasNext) OratureColors.RegularText else OratureColors.RegularText.copy(alpha = 0.3f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun ChapterGrid(
    chapters: List<OratureChapterGridItem>,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chapters.chunked(GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item -> ChapterCell(item, onSelect) }
                // Pad the final row so every row keeps the same width.
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.size(40.dp)) }
            }
        }
    }
}

@Composable
private fun ChapterCell(
    item: OratureChapterGridItem,
    onSelect: (Int) -> Unit
) {
    val background = if (item.selected) OratureColors.Primary else Color.Transparent
    val contentColor = if (item.selected) OratureColors.OnPrimary else OratureColors.Primary

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(background, RoundedCornerShape(8.dp))
            .border(1.dp, OratureColors.Primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable { onSelect(item.sort) },
        contentAlignment = Alignment.Center
    ) {
        Text(text = item.title, color = contentColor, fontWeight = FontWeight.SemiBold)
        if (item.completed) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (item.selected) OratureColors.OnPrimary else OratureColors.Primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(12.dp)
                    .background(background, CircleShape)
            )
        }
    }
}
