package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureProjectGroupUiModel
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.dialect
import org.bibletranslationtools.orature.resources.`import`
import org.bibletranslationtools.orature.resources.narration
import org.bibletranslationtools.orature.resources.newProject
import org.bibletranslationtools.orature.resources.sourceLanguage
import org.bibletranslationtools.orature.resources.targetLanguage
import org.bibletranslationtools.orature.resources.translation

/** Localized label for a [ProjectMode], mirroring the JVM app's `messages[mode.titleKey]`. */
@Composable
fun projectModeLabel(mode: ProjectMode): String = when (mode) {
    ProjectMode.TRANSLATION -> stringResource(Res.string.translation)
    ProjectMode.NARRATION -> stringResource(Res.string.narration)
    ProjectMode.DIALECT -> stringResource(Res.string.dialect)
}

/**
 * A project-GROUP card for the projects pane, matching TranslationCard2: title is
 * "{resourceSlug} {mode}" (e.g. "ULB Narration"), with source/target language rows below.
 * Selected group gets a highlighted primary border.
 */
@Composable
fun OratureProjectGroupCard(
    group: OratureProjectGroupUiModel,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val border = if (isSelected) {
        BorderStroke(2.dp, OratureColors.Primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "${group.resourceSlug.uppercase()} ${projectModeLabel(group.mode)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${stringResource(Res.string.sourceLanguage)}: ${group.sourceLanguageName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "${stringResource(Res.string.targetLanguage)}: ${group.targetLanguageName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** The dashed "new project" affordance card pinned at the top of the projects pane. */
@Composable
fun OratureNewProjectCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, OratureColors.Primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(PaddingValues(vertical = 14.dp, horizontal = 12.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = OratureColors.Primary)
            Text(
                text = stringResource(Res.string.newProject),
                color = OratureColors.Primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Secondary-style "Import" button pinned at the bottom of the projects pane. */
@Composable
fun OratureImportButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Icon(imageVector = Icons.Filled.Download, contentDescription = null)
        Text(text = stringResource(Res.string.`import`), modifier = Modifier.padding(start = 8.dp))
    }
}
