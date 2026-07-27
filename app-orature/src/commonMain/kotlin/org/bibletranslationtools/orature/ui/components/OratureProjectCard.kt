package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// JVM translation-card-2.css dimensions.
private val CardShape = RoundedCornerShape(16.dp)
private val CardBorderWidth = 2.dp

/** Localized label for a [ProjectMode], mirroring the JVM app's `messages[mode.titleKey]`. */
@Composable
fun projectModeLabel(mode: ProjectMode): String = when (mode) {
    ProjectMode.TRANSLATION -> stringResource(Res.string.translation)
    ProjectMode.NARRATION -> stringResource(Res.string.narration)
    ProjectMode.DIALECT -> stringResource(Res.string.dialect)
}

/**
 * A project-GROUP card for the projects pane (JVM: `TranslationCard2`). The card title is
 * "{resourceSlug} {mode}" (e.g. "ULB Narration"). The visual has two states, matching the JVM's
 * two skins:
 *  - **inactive** (`TranslationCardSkin2`): a grey 2px border and a compact body — source slug,
 *    a centered double-chevron, target slug.
 *  - **active/selected** (`ActiveTranslationCardSkin`): a primary-blue 2px border, an info icon in
 *    the header, and a detailed body — "Source Language" + name w/ ear icon, a divider, then
 *    "Target Language" + name w/ voice icon.
 */
@Composable
fun OratureProjectGroupCard(
    group: OratureProjectGroupUiModel,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) OratureColors.Primary else OratureColors.SurfaceTertiary
    val title = "${group.resourceSlug.uppercase()} ${projectModeLabel(group.mode)}"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .clickable(onClick = onClick)
            .background(OratureColors.Foreground, CardShape)
            .border(BorderStroke(CardBorderWidth, borderColor), CardShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isSelected) ActiveCardContent(title, group) else InactiveCardContent(title, group)
    }
}

@Composable
private fun InactiveCardContent(title: String, group: OratureProjectGroupUiModel) {
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OratureColors.RegularText)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(group.key.sourceLanguageSlug, fontSize = 20.sp, color = OratureColors.RegularText)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.KeyboardDoubleArrowRight,
                contentDescription = null,
                tint = OratureColors.RegularText,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(group.key.targetLanguageSlug, fontSize = 20.sp, color = OratureColors.RegularText)
    }
}

@Composable
private fun ActiveCardContent(title: String, group: OratureProjectGroupUiModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OratureColors.RegularText, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.Info, contentDescription = null, tint = OratureColors.RegularText, modifier = Modifier.size(22.dp))
    }
    LanguageRow(stringResource(Res.string.sourceLanguage), group.sourceLanguageName, Icons.Filled.Hearing)
    HorizontalDivider(color = OratureColors.SurfaceTertiary)
    LanguageRow(stringResource(Res.string.targetLanguage), group.targetLanguageName, Icons.Filled.RecordVoiceOver)
}

@Composable
private fun LanguageRow(subtitle: String, languageName: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(subtitle, fontSize = 14.sp, color = OratureColors.RegularText)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = OratureColors.RegularText, modifier = Modifier.size(22.dp))
            Text(languageName, fontSize = 20.sp, color = OratureColors.RegularText, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * The "new project" affordance (JVM: `TranslationCreationCard`): a filled grey rounded card with
 * placeholder graphic bars on the left and a primary-blue "+" button on the right.
 */
@Composable
fun OratureNewProjectCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(CardShape)
            .background(OratureColors.CardPlaceholderBackground, CardShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Placeholder graphic bars (JVM card-graphic rects): a 140-wide bar, then a row of
        // 80 + small + 80. Pill-shaped (arc 20 on 16-tall rects), filled #E5E8EB.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaceholderBar(width = 140.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaceholderBar(width = 80.dp)
                PlaceholderBar(width = 15.dp, height = 15.dp)
                PlaceholderBar(width = 80.dp)
            }
        }
        Spacer(Modifier.weight(1f))
        // Primary "+" button (JVM: btn btn--primary with MDI_PLUS).
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(OratureColors.Primary)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.newProject), tint = OratureColors.OnPrimary)
        }
    }
}

@Composable
private fun PlaceholderBar(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp = 16.dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(OratureColors.CardGraphic, RoundedCornerShape(percent = 50))
    )
}

/**
 * Secondary-style "Import" button pinned at the bottom of the projects pane (JVM: `btn btn--secondary`):
 * white surface, 2px primary-blue border, 12dp radius, blue icon + text, full width and tall.
 */
@Composable
fun OratureImportButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, OratureColors.Primary),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = OratureColors.Foreground,
            contentColor = OratureColors.Primary
        )
    ) {
        Icon(imageVector = Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(24.dp))
        Text(
            text = stringResource(Res.string.`import`),
            fontSize = 20.sp,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}
