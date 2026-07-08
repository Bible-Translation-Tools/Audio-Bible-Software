package org.bibletranslationtools.bttrecorder2.ui.screens.wizard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.otter.common.data.primitives.Collection

@Composable
fun BookSelectionScreen(
    books: List<Collection>,
    searchQuery: String = "",
    onBookSelected: (Collection) -> Unit
) {
    // Search behavior matches the original BTT-Recorder GenericAdapter:
    // prefix-match on slug or name, then sort with slug-prefix matches floated
    // to the top. The book's `titleKey` plays the role of `name` here, since
    // that's what's shown in the row. See WizardFilter.kt.
    val filtered = remember(books, searchQuery) {
        filterAndSortStartsWith(
            items = books,
            query = searchQuery,
            slugOf = { it.slug },
            nameOf = { it.titleKey }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Choose a Book",
            style = MaterialTheme.typography.titleMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.slug }) { book ->
                SelectionRow(
                    title = book.titleKey,
                    slug = book.slug,
                    leadingIcon = Icons.AutoMirrored.Filled.MenuBook,
                    onClick = { onBookSelected(book) }
                )
            }
        }
    }
}
