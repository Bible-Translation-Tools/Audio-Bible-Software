package org.bibletranslationtools.bttrecorder2.ui.screens.wizard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.components.sourceEditionText
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.EditionOption
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.label_newest_edition
import org.bibletranslationtools.shared.resources.label_older_edition
import org.bibletranslationtools.shared.resources.wizard_select_edition
import org.bibletranslationtools.shared.resources.wizard_select_edition_hint
import org.jetbrains.compose.resources.stringResource

/**
 * Edition step, shown only when the chosen source has more than one edition installed. Lists
 * them newest first; the newest is highlighted as the default choice and older ones are marked.
 */
@Composable
fun EditionSelectionScreen(
    editions: List<EditionOption>,
    onEditionSelected: (ResourceMetadata) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(Res.string.wizard_select_edition),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
        )
        Text(
            text = stringResource(Res.string.wizard_select_edition_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(editions, key = { it.summary.edition.id }) { option ->
                val edition = option.summary.edition
                ListItem(
                    headlineContent = { Text(sourceEditionText(edition, option.summary.distinguishingCode)) },
                    supportingContent = { Text(edition.title) },
                    trailingContent = {
                        when {
                            option.isNewest -> Text(
                                stringResource(Res.string.label_newest_edition),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            option.isOlder -> Text(
                                stringResource(Res.string.label_older_edition),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = if (option.isNewest) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier.clickable { onEditionSelected(edition) }
                )
                HorizontalDivider()
            }
        }
    }
}
