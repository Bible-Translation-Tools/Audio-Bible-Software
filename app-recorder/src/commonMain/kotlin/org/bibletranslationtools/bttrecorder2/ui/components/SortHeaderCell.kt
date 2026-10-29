package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SortDirection
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.pm_sort_asc
import org.bibletranslationtools.shared.resources.pm_sort_desc
import org.jetbrains.compose.resources.stringResource

/** A sortable column title; the active one is highlighted and announces its [direction]. */
@Composable
internal fun SortHeaderCell(label: String, isActive: Boolean, direction: SortDirection, onClick: () -> Unit) {
    val color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val directionText = stringResource(
        if (direction == SortDirection.ASC) Res.string.pm_sort_asc else Res.string.pm_sort_desc
    )
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (isActive) stateDescription = directionText },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = color
        )
        Icon(
            imageVector = Icons.Default.SwapVert,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(28.dp)
        )
    }
}
