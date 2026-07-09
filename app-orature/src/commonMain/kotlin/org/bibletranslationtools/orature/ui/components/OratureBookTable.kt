package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.bibletranslationtools.orature.resources.options
import org.bibletranslationtools.orature.resources.progress

/** Localized label for an [Anthology], mirroring the JVM app's `messages[anthology.titleKey]`. */
@Composable
fun anthologyLabel(anthology: Anthology): String = when (anthology) {
    Anthology.OLD_TESTAMENT -> stringResource(Res.string.oldTestament)
    Anthology.NEW_TESTAMENT -> stringResource(Res.string.newTestament)
    Anthology.OTHER -> ""
}

/**
 * The book table for the selected project group: a styled header row (Book | Code |
 * Anthology | Progress) followed by rows, matching WorkBookTableView's column order.
 * Each row has a stub per-row overflow (⋮) affordance.
 */
@Composable
fun OratureBookTable(
    books: List<OratureBookUiModel>,
    onBookClick: (OratureBookUiModel) -> Unit,
    onRowOptionsClick: (OratureBookUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                OratureBookTableHeader()
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            items(books, key = { it.id }) { book ->
                OratureBookTableRow(
                    book = book,
                    onClick = { onBookClick(book) },
                    onOptionsClick = { onRowOptionsClick(book) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun OratureBookTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(Res.string.book),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.34f)
        )
        Text(
            text = stringResource(Res.string.code),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.16f)
        )
        Text(
            text = stringResource(Res.string.anthology),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.24f)
        )
        Text(
            text = stringResource(Res.string.progress),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.18f)
        )
        // Reserve space for the trailing per-row overflow button so columns line up.
        Spacer(modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun OratureBookTableRow(
    book: OratureBookUiModel,
    onClick: () -> Unit,
    onOptionsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.34f)
        )
        Text(
            text = book.slug,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.16f)
        )
        Text(
            text = anthologyLabel(book.anthology),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.24f)
        )
        LinearProgressIndicator(
            progress = { book.progress.toFloat().coerceIn(0f, 1f) },
            color = OratureColors.Primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(0.18f).padding(end = 8.dp)
        )
        IconButton(onClick = onOptionsClick, modifier = Modifier.width(40.dp)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(Res.string.options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
