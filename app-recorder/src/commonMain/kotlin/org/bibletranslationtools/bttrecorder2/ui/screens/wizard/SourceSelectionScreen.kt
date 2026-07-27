package org.bibletranslationtools.bttrecorder2.ui.screens.wizard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata

/**
 * Source-selection step. Lists sources already imported into the DB, then the bundled
 * gateway-language sources that aren't imported yet — selecting one of those sideloads it on
 * demand (mirrors Orature's wizard). Both groups are prefix-searchable (the list is now long
 * once the bundled gateway sources are surfaced).
 */
@Composable
fun SourceSelectionScreen(
    sources: List<ResourceMetadata>,
    availableSources: List<Language>,
    searchQuery: String = "",
    onSourceSelected: (ResourceMetadata) -> Unit,
    onAvailableSourceSelected: (Language) -> Unit
) {
    val filteredSources = remember(sources, searchQuery) {
        filterAndSortStartsWith(
            items = sources,
            query = searchQuery,
            slugOf = { it.language.slug },
            nameOf = { it.language.name }
        )
    }
    val filteredAvailable = remember(availableSources, searchQuery) {
        filterAndSortStartsWith(
            items = availableSources,
            query = searchQuery,
            slugOf = { it.slug },
            nameOf = { it.name }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Select Source",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredSources, key = { "imported:${it.id}" }) { source ->
                ListItem(
                    headlineContent = { Text(source.title) },
                    supportingContent = { Text(source.language.name) },
                    modifier = Modifier.clickable { onSourceSelected(source) }
                )
                HorizontalDivider()
            }
            items(filteredAvailable, key = { "available:${it.slug}" }) { language ->
                ListItem(
                    headlineContent = { Text(language.name) },
                    supportingContent = { Text(language.slug) },
                    modifier = Modifier.clickable { onAvailableSourceSelected(language) }
                )
                HorizontalDivider()
            }
        }
    }
}
