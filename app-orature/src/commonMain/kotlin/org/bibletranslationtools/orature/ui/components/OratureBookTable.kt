package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureBookUiModel
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.otter.common.data.primitives.Anthology
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.anthology
import org.bibletranslationtools.orature.resources.book
import org.bibletranslationtools.orature.resources.code
import org.bibletranslationtools.orature.resources.newTestament
import org.bibletranslationtools.orature.resources.oldTestament
import org.bibletranslationtools.orature.resources.backup
import org.bibletranslationtools.orature.resources.deleteBook
import org.bibletranslationtools.orature.resources.exportOptions
import org.bibletranslationtools.orature.resources.openBook
import org.bibletranslationtools.orature.resources.options
import org.bibletranslationtools.orature.resources.progress

// Column weights (the two trailing icon columns are fixed width).
private const val WEIGHT_BOOK = 0.34f
private const val WEIGHT_CODE = 0.16f
private const val WEIGHT_ANTHOLOGY = 0.22f
private const val WEIGHT_PROGRESS = 0.18f
private val IconColWidth = 44.dp

/** The sortable columns (JVM: WorkBookTableView isSortable columns — incl. the source-audio column). */
private enum class BookSortColumn { BOOK, CODE, ANTHOLOGY, PROGRESS, SOURCE_AUDIO }

/** Localized label for an [Anthology], mirroring the JVM app's `messages[anthology.titleKey]`. */
@Composable
fun anthologyLabel(anthology: Anthology): String = when (anthology) {
    Anthology.OLD_TESTAMENT -> stringResource(Res.string.oldTestament)
    Anthology.NEW_TESTAMENT -> stringResource(Res.string.newTestament)
    Anthology.OTHER -> ""
}

/**
 * The book table for the selected project group (JVM: `WorkBookTableView`): a grey, sortable header
 * row (Book | Code | Anthology | Progress | source-audio | options) over the book rows. Clicking a
 * column header cycles ascending → descending → default (biblical `sort`) order, mirroring the JVM
 * custom sort policy. Books with source audio show a speaker icon.
 */
@Composable
fun OratureBookTable(
    books: List<OratureBookUiModel>,
    onBookClick: (OratureBookUiModel) -> Unit,
    onBackupBook: (OratureBookUiModel) -> Unit,
    onExportBook: (OratureBookUiModel) -> Unit,
    onDeleteBook: (OratureBookUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    var sortColumn by remember { mutableStateOf<BookSortColumn?>(null) }
    var ascending by remember { mutableStateOf(true) }

    val sortedBooks = remember(books, sortColumn, ascending) {
        val base = when (sortColumn) {
            null -> books.sortedBy { it.sort } // default biblical order
            BookSortColumn.BOOK -> books.sortedBy { it.title.lowercase() }
            BookSortColumn.CODE -> books.sortedBy { it.slug.lowercase() }
            BookSortColumn.ANTHOLOGY -> books.sortedBy { it.anthology.ordinal }
            BookSortColumn.PROGRESS -> books.sortedBy { it.progress }
            BookSortColumn.SOURCE_AUDIO -> books.sortedBy { it.hasSourceAudio }
        }
        if (sortColumn != null && !ascending) base.reversed() else base
    }

    // Cycle: unsorted → asc → desc → unsorted (JVM resets to default order when toggled off).
    fun onSort(column: BookSortColumn) {
        when {
            sortColumn != column -> { sortColumn = column; ascending = true }
            ascending -> ascending = false
            else -> sortColumn = null
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Reserve room on the right so the desktop scrollbar doesn't overlap the row content.
            modifier = Modifier.fillMaxSize().padding(end = 12.dp)
        ) {
            headerRow(sortColumn, ascending, ::onSort)
            items(sortedBooks, key = { it.id }) { book ->
                OratureBookTableRow(
                    book = book,
                    onClick = { onBookClick(book) },
                    onBackup = { onBackupBook(book) },
                    onExport = { onExportBook(book) },
                    onDelete = { onDeleteBook(book) }
                )
                HorizontalDivider(color = OratureColors.SurfaceTertiary.copy(alpha = 0.6f))
            }
        }
        OratureVerticalScrollbar(
            listState = listState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun LazyListScope.headerRow(
    sortColumn: BookSortColumn?,
    ascending: Boolean,
    onSort: (BookSortColumn) -> Unit
) {
    // Sticky so the column headers stay pinned while the book rows scroll (JVM: fixed table header).
    stickyHeader {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OratureColors.TableHeaderBackground)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderCell(stringResource(Res.string.book), WEIGHT_BOOK, BookSortColumn.BOOK, sortColumn, ascending, onSort)
            HeaderCell(stringResource(Res.string.code), WEIGHT_CODE, BookSortColumn.CODE, sortColumn, ascending, onSort)
            HeaderCell(stringResource(Res.string.anthology), WEIGHT_ANTHOLOGY, BookSortColumn.ANTHOLOGY, sortColumn, ascending, onSort)
            HeaderCell(stringResource(Res.string.progress), WEIGHT_PROGRESS, BookSortColumn.PROGRESS, sortColumn, ascending, onSort)
            // Source-audio column — sortable (JVM: sortable Boolean column), a speaker icon as its header.
            SourceAudioHeaderCell(sortColumn, ascending, onSort)
            // Options column — not sortable.
            Box(modifier = Modifier.width(IconColWidth))
        }
    }
}

@Composable
private fun SourceAudioHeaderCell(
    sortColumn: BookSortColumn?,
    ascending: Boolean,
    onSort: (BookSortColumn) -> Unit
) {
    Row(
        modifier = Modifier.width(IconColWidth).clickable { onSort(BookSortColumn.SOURCE_AUDIO) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = OratureColors.RegularText80,
            modifier = Modifier.size(18.dp)
        )
        if (sortColumn == BookSortColumn.SOURCE_AUDIO) {
            Icon(
                imageVector = if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = OratureColors.RegularText80,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun RowScope.HeaderCell(
    label: String,
    weight: Float,
    column: BookSortColumn,
    sortColumn: BookSortColumn?,
    ascending: Boolean,
    onSort: (BookSortColumn) -> Unit
) {
    Row(
        modifier = Modifier.weight(weight).clickable { onSort(column) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = OratureColors.RegularText80
        )
        if (sortColumn == column) {
            Icon(
                imageVector = if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = OratureColors.RegularText80,
                modifier = Modifier.padding(start = 4.dp).size(16.dp)
            )
        }
    }
}

@Composable
private fun OratureBookTableRow(
    book: OratureBookUiModel,
    onClick: () -> Unit,
    onBackup: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OratureColors.Foreground)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = OratureColors.RegularText,
            modifier = Modifier.weight(WEIGHT_BOOK)
        )
        Text(
            text = book.slug,
            style = MaterialTheme.typography.bodyMedium,
            color = OratureColors.RegularText80,
            modifier = Modifier.weight(WEIGHT_CODE)
        )
        Text(
            text = anthologyLabel(book.anthology),
            style = MaterialTheme.typography.bodyMedium,
            color = OratureColors.RegularText80,
            modifier = Modifier.weight(WEIGHT_ANTHOLOGY)
        )
        LinearProgressIndicator(
            progress = { book.progress.toFloat().coerceIn(0f, 1f) },
            color = OratureColors.Primary,
            trackColor = OratureColors.SurfaceTertiary,
            modifier = Modifier.weight(WEIGHT_PROGRESS).padding(end = 8.dp)
        )
        // Source-audio status icon (JVM: WorkbookSourceAudioTableCell → speaker when hasSourceAudio).
        Box(modifier = Modifier.width(IconColWidth), contentAlignment = Alignment.Center) {
            if (book.hasSourceAudio) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = OratureColors.Primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        // Per-row options (JVM: WorkbookOptionTableCell → horizontal dots).
        Box(modifier = Modifier.width(IconColWidth), contentAlignment = Alignment.Center) {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = stringResource(Res.string.options),
                    tint = OratureColors.RegularText80
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                // JVM: WorkbookOptionMenu — Open Book, Backup, Export, Delete Book (in that order).
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.openBook)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                    onClick = { menuExpanded = false; onClick() }
                )
                // Quick Backup: pick a directory and export a Backup archive immediately, no dialog
                // (JVM: WorkbookQuickBackupEvent → chooseDirectory → export(BACKUP) directly).
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.backup)) },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    onClick = { menuExpanded = false; onBackup() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.exportOptions)) },
                    leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                    onClick = { menuExpanded = false; onExport() }
                )
                // Delete Book resets the book to its initial state (JVM: deleteBook — deletes takes).
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.deleteBook), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuExpanded = false; onDelete() }
                )
            }
        }
    }
}
