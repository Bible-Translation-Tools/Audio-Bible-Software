package org.bibletranslationtools.bttrecorder2.ui.screens.wizard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.otter.common.data.primitives.Language

@Composable
fun TargetLanguageSelectionScreen(
    languages: List<Language>,
    searchQuery: String = "",
    onLanguageSelected: (Language) -> Unit
) {
    // Search behavior matches the original BTT-Recorder TargetLanguageAdapter:
    // prefix-match on slug or name, then sort with slug-prefix matches floated
    // to the top. See WizardFilter.kt for the ported logic.
    val filtered = remember(languages, searchQuery) {
        filterAndSortStartsWith(
            items = languages,
            query = searchQuery,
            slugOf = { it.slug },
            nameOf = { it.name }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Choose Target Language",
            style = MaterialTheme.typography.titleMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.slug }) { language ->
                SelectionRow(
                    title = language.anglicizedName.ifBlank { language.name },
                    slug = language.slug,
                    onClick = { onLanguageSelected(language) }
                )
            }
        }
    }
}
