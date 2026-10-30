package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProgressFilter
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.cd_clear_search
import org.bibletranslationtools.shared.resources.cd_filter
import org.bibletranslationtools.shared.resources.ic_status_in_progress
import org.bibletranslationtools.shared.resources.ic_status_not_started
import org.bibletranslationtools.shared.resources.pm_filter_completed
import org.bibletranslationtools.shared.resources.pm_filter_in_progress
import org.bibletranslationtools.shared.resources.pm_filter_not_started
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The project lists' search field, and their Not started / In progress / Completed filter when [onFilterChange] is given. */
@Composable
internal fun SearchFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    filter: ProgressFilter? = null,
    onFilterChange: ((ProgressFilter?) -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    var filterMenuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) null else {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.cd_clear_search))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = colors.surface,
                focusedContainerColor = colors.surface,
                unfocusedBorderColor = colors.outlineVariant
            ),
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 380.dp).fillMaxWidth()
        )
        if (onFilterChange != null) {
            Box {
                FilledIconButton(
                    onClick = { filterMenuExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (filter != null) {
                            colors.primaryContainer
                        } else colors.onSurface.copy(alpha = 0.06f),
                        contentColor = if (filter != null) colors.primary else colors.onSurface
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Rounded.FilterAlt,
                        contentDescription = stringResource(Res.string.cd_filter),
                        modifier = Modifier.size(28.dp)
                    )
                }
                FilterMenu(
                    expanded = filterMenuExpanded,
                    selected = filter,
                    onSelect = {
                        // Picking the active filter again clears it.
                        onFilterChange(if (it == filter) null else it)
                        filterMenuExpanded = false
                    },
                    onDismiss = { filterMenuExpanded = false }
                )
            }
        }
    }
}

@Composable
private fun FilterMenu(
    expanded: Boolean,
    selected: ProgressFilter?,
    onSelect: (ProgressFilter) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(10.dp),
        containerColor = colors.surface,
        border = BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.1f)),
        modifier = Modifier.widthIn(min = 292.dp)
    ) {
        Text(
            text = stringResource(Res.string.cd_filter),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        listOf(
            Triple(
                ProgressFilter.NOT_STARTED,
                painterResource(Res.drawable.ic_status_not_started),
                Res.string.pm_filter_not_started
            ),
            Triple(
                ProgressFilter.IN_PROGRESS,
                painterResource(Res.drawable.ic_status_in_progress),
                Res.string.pm_filter_in_progress
            ),
            Triple(
                ProgressFilter.COMPLETED,
                rememberVectorPainter(Icons.Outlined.Task),
                Res.string.pm_filter_completed
            )
        ).forEach { (option, icon, label) ->
            DropdownMenuItem(
                text = {
                    Text(stringResource(label), style = MaterialTheme.typography.titleLarge)
                },
                leadingIcon = {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
                },
                onClick = { onSelect(option) },
                modifier = Modifier
                    .background(
                        if (option == selected) colors.primaryContainer else Color.Transparent
                    )
            )
        }
    }
}
