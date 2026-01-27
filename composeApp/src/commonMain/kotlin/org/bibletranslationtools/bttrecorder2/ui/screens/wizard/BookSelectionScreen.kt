package org.bibletranslationtools.bttrecorder2.ui.screens.wizard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.otter.common.data.primitives.Collection

@Composable
fun BookSelectionScreen(
    books: List<Collection>,
    onBookSelected: (Collection) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Select Book",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(books) { book ->
                ListItem(
                    headlineContent = { Text(book.titleKey) }, // Using titleKey as typical title
                    modifier = Modifier.clickable { onBookSelected(book) }
                )
                HorizontalDivider()
            }
        }
    }
}
