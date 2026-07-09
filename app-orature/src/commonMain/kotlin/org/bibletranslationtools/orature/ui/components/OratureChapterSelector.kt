package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureChapterGridItem

private const val GRID_COLUMNS = 5

/**
 * Orature's chapter selector (JVM: `ChapterSelector` + `ChapterSelectorPopup`/`ChapterGrid`):
 * previous / title / next controls, where the title button opens a 5-column grid popup of
 * chapter numbers. The selected chapter is highlighted; completed chapters show a check.
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

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onPrevious, enabled = hasPrevious) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = null)
        }

        Box {
            Row(
                modifier = Modifier
                    .clickable(enabled = chapters.isNotEmpty()) { gridOpen = true }
                    .background(OratureColors.SurfaceSecondary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = OratureColors.Primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(text = chapterTitle, fontWeight = FontWeight.SemiBold)
            }

            DropdownMenu(expanded = gridOpen, onDismissRequest = { gridOpen = false }) {
                ChapterGrid(
                    chapters = chapters,
                    onSelect = {
                        gridOpen = false
                        onSelectChapter(it)
                    }
                )
            }
        }

        IconButton(onClick = onNext, enabled = hasNext) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
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
