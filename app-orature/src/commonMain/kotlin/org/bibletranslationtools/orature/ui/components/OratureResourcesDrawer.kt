package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.cancel
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.findResources
import org.bibletranslationtools.orature.resources.getResourcesComingSoon
import org.bibletranslationtools.orature.resources.getResourcesTitle
import org.bibletranslationtools.orature.resources.removeResource
import org.bibletranslationtools.orature.resources.removeResourceInUse
import org.bibletranslationtools.orature.resources.removeResourceMessage
import org.bibletranslationtools.orature.resources.removeResourceTitle
import org.bibletranslationtools.orature.resources.resourceHeldBack
import org.bibletranslationtools.orature.resources.resourceLinkedTo
import org.bibletranslationtools.orature.resources.resourceRemoveFailed
import org.bibletranslationtools.orature.resources.resourceRemoved
import org.bibletranslationtools.orature.resources.resourceSize
import org.bibletranslationtools.orature.resources.resourceSizeWithAudio
import org.bibletranslationtools.orature.resources.resourceTypeBible
import org.bibletranslationtools.orature.resources.resourceTypeHelp
import org.bibletranslationtools.orature.resources.resourceUnused
import org.bibletranslationtools.orature.resources.resourceUsedBy
import org.bibletranslationtools.orature.resources.resourcesDescription
import org.bibletranslationtools.orature.resources.resourcesEmpty
import org.bibletranslationtools.orature.resources.resourcesSummary
import org.bibletranslationtools.orature.resources.resourcesTitle
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureResourceRemoval
import org.bibletranslationtools.orature.ui.viewmodels.OratureResourcesViewModel
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledResource
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledResources
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform.getKoin

/**
 * The Resources drawer: the source texts and source audio installed on this device, grouped by
 * language, with what each takes on disk and what uses it. A resource nothing uses can be removed;
 * nothing is ever removed automatically. Finding and downloading resources comes later.
 */
@Composable
fun OratureResourcesDrawer(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OratureResourcesViewModel = viewModel { OratureResourcesViewModel(getKoin().get<InstalledResources>()) }
) {
    val state by viewModel.uiState.collectAsState()
    var confirming by remember { mutableStateOf<InstalledResource?>(null) }

    // Every time the drawer opens: what uses each resource changes while it's closed.
    LaunchedEffect(Unit) { viewModel.onOpened() }

    confirming?.let { resource ->
        val name = resourceName(resource)
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(Res.string.removeResourceTitle, name)) },
            text = { Text(stringResource(Res.string.removeResourceMessage, megabytes(resource.sizeBytes))) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    viewModel.remove(resource)
                }) { Text(stringResource(Res.string.removeResource), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.cancel)) } }
        )
    }

    Surface(modifier = modifier.width(550.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.resourcesTitle),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.close), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(stringResource(Res.string.resourcesDescription), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)

            state.lastRemoval?.let { removal ->
                val name = resourceName(removal.resource)
                Text(
                    text = when (removal) {
                        is OratureResourceRemoval.Removed -> stringResource(Res.string.resourceRemoved, name)
                        is OratureResourceRemoval.Failed -> stringResource(Res.string.resourceRemoveFailed, name)
                    },
                    fontSize = 14.sp,
                    color = if (removal is OratureResourceRemoval.Failed) MaterialTheme.colorScheme.error else OratureColors.NoteText
                )
            }

            when {
                state.isLoading -> CircularProgressIndicator(color = OratureColors.Primary)
                state.error != null -> Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                state.resources.isEmpty() -> Text(stringResource(Res.string.resourcesEmpty), color = OratureColors.NoteText)
                else -> {
                    Text(
                        stringResource(Res.string.resourcesSummary, state.resources.size, megabytes(state.totalBytes)),
                        fontSize = 14.sp,
                        color = OratureColors.NoteText
                    )
                    state.resources.groupBy { it.edition.language.slug }.values.forEach { inLanguage ->
                        val language = inLanguage.first().edition.language
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = language.name.takeIf { it != language.anglicizedName }
                                    ?.let { "${language.anglicizedName} ($it)" } ?: language.anglicizedName,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            inLanguage.forEach { resource ->
                                ResourceRow(
                                    resource = resource,
                                    removing = state.removingId == resource.edition.id,
                                    onRemove = { confirming = resource }
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = OratureColors.SurfaceTertiary)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(Res.string.getResourcesTitle),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(stringResource(Res.string.getResourcesComingSoon), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                OutlinedButton(onClick = {}, enabled = false, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(Res.string.findResources), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ResourceRow(resource: InstalledResource, removing: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(resourceName(resource), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            val type = stringResource(
                if (resource.edition.type == ContainerType.Help) Res.string.resourceTypeHelp else Res.string.resourceTypeBible
            )
            val size = if (resource.audioBytes > 0) {
                stringResource(Res.string.resourceSizeWithAudio, megabytes(resource.sizeBytes), megabytes(resource.audioBytes))
            } else {
                stringResource(Res.string.resourceSize, megabytes(resource.sizeBytes))
            }
            Text("${resource.edition.title} · $type", fontSize = 13.sp, color = OratureColors.NoteText)
            Text(size, fontSize = 13.sp, color = OratureColors.NoteText)
            usageLines(resource).forEach { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface) }
        }
        if (removing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = OratureColors.Primary)
        } else {
            IconButton(onClick = onRemove, enabled = resource.removable) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(
                        if (resource.removable) Res.string.removeResource else Res.string.removeResourceInUse
                    ),
                    tint = if (resource.removable) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@Composable
private fun usageLines(resource: InstalledResource): List<String> = buildList {
    if (resource.usedByLanguages.isNotEmpty()) {
        add(stringResource(Res.string.resourceUsedBy, resource.usedByLanguages.joinToString(", ") { it.anglicizedName }))
    }
    if (resource.heldBackChapters > 0) add(stringResource(Res.string.resourceHeldBack, resource.heldBackChapters))
    if (resource.linkedTo.isNotEmpty()) {
        add(stringResource(Res.string.resourceLinkedTo, resource.linkedTo.joinToString(", ") { it.identifier.uppercase() }))
    }
    if (isEmpty()) add(stringResource(Res.string.resourceUnused))
}

@Composable
private fun resourceName(resource: InstalledResource) = sourceEditionText(resource.edition, resource.distinguishingCode)

/** Whole megabytes, "< 1" below one; the unit is in the strings. */
private fun megabytes(bytes: Long): String {
    if (bytes <= 0) return "0"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb < 1) "< 1" else "${mb.toLong()}"
}
