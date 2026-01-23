package org.bibletranslationtools.bttrecorder2.ui.screens.wizard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata

@Composable
fun SourceSelectionScreen(
    sources: List<ResourceMetadata>,
    onSourceSelected: (ResourceMetadata) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Select Source",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(sources) { source ->
                ListItem(
                    headlineContent = { Text(source.title) },
                    supportingContent = { Text(source.language.name) },
                    modifier = Modifier.clickable { onSourceSelected(source) }
                )
                HorizontalDivider()
            }
        }
    }
}
