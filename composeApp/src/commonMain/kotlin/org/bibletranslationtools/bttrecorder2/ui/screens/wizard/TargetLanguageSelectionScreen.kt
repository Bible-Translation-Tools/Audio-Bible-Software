package org.bibletranslationtools.bttrecorder2.ui.screens.wizard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.otter.common.data.primitives.Language

@Composable
fun TargetLanguageSelectionScreen(
    languages: List<Language>,
    onLanguageSelected: (Language) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Select Target Language",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(languages) { language ->
                ListItem(
                    headlineContent = { Text(language.name) },
                    supportingContent = { Text(language.slug) },
                    modifier = Modifier.clickable { onLanguageSelected(language) }
                )
                HorizontalDivider()
            }
        }
    }
}
