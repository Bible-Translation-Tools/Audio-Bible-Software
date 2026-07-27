package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.anglicized
import org.bibletranslationtools.orature.resources.code
import org.bibletranslationtools.orature.resources.dialect
import org.bibletranslationtools.orature.resources.dialectDesc
import org.bibletranslationtools.orature.resources.goBack
import org.bibletranslationtools.orature.resources.language
import org.bibletranslationtools.orature.resources.narration
import org.bibletranslationtools.orature.resources.narrationDesc
import org.bibletranslationtools.orature.resources.oralTranslation
import org.bibletranslationtools.orature.resources.oralTranslationDesc
import org.bibletranslationtools.orature.resources.search
import org.bibletranslationtools.orature.resources.selectProjectTypeStep1
import org.bibletranslationtools.orature.resources.selectSourceLanguageStep2
import org.bibletranslationtools.orature.resources.selectSourceVersionStep4
import org.bibletranslationtools.orature.resources.selectTargetLanguageStep3
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureResourceVersion
import org.bibletranslationtools.orature.ui.viewmodels.WizardStep
import org.bibletranslationtools.orature.ui.viewmodels.WizardUiState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Orature's project-creation wizard, docked in the home center pane in place of the book
 * table (ported from the JVM `ProjectWizardSection`). Each step renders a header (back arrow
 * + title, plus a search field for the language steps) over a body; the container reads the
 * whole flow from [WizardUiState].
 */
@Composable
fun OratureProjectWizardSection(
    state: WizardUiState,
    onModeSelected: (ProjectMode) -> Unit,
    onBack: () -> Unit,
    onLanguageSelected: (Language) -> Unit,
    onResourceVersionSelected: (OratureResourceVersion) -> Unit,
    onSourceSearchQueryChange: (String) -> Unit,
    onTargetSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OratureColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (state.step) {
                WizardStep.SELECT_TYPE -> {
                    WizardHeader(title = Res.string.selectProjectTypeStep1, onBack = onBack)
                    Spacer(Modifier.height(16.dp))
                    SelectTypeBody(onModeSelected = onModeSelected)
                }

                WizardStep.SELECT_SOURCE_LANGUAGE -> {
                    WizardHeader(
                        title = Res.string.selectSourceLanguageStep2,
                        onBack = onBack,
                        searchQuery = state.sourceLanguageSearchQuery,
                        onSearchQueryChange = onSourceSearchQueryChange
                    )
                    Spacer(Modifier.height(16.dp))
                    LanguageTable(
                        languages = state.visibleSourceLanguages,
                        onLanguageSelected = onLanguageSelected
                    )
                }

                WizardStep.SELECT_TARGET_LANGUAGE -> {
                    WizardHeader(
                        title = Res.string.selectTargetLanguageStep3,
                        onBack = onBack,
                        searchQuery = state.targetLanguageSearchQuery,
                        onSearchQueryChange = onTargetSearchQueryChange
                    )
                    Spacer(Modifier.height(16.dp))
                    LanguageTable(
                        languages = state.visibleTargetLanguages,
                        onLanguageSelected = onLanguageSelected
                    )
                }

                WizardStep.SELECT_VERSION -> {
                    WizardHeader(title = Res.string.selectSourceVersionStep4, onBack = onBack)
                    Spacer(Modifier.height(16.dp))
                    ResourceVersionTable(
                        versions = state.resourceVersions,
                        onVersionSelected = onResourceVersionSelected
                    )
                }
            }
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun WizardHeader(
    title: StringResource,
    onBack: () -> Unit,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.goBack),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(start = 4.dp)
        )
        if (searchQuery != null && onSearchQueryChange != null) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.search)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(Res.string.search))
                },
                modifier = Modifier.width(240.dp)
            )
        }
    }
}

@Composable
private fun SelectTypeBody(onModeSelected: (ProjectMode) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TranslationTypeCard(
            title = Res.string.oralTranslation,
            description = Res.string.oralTranslationDesc,
            onClick = { onModeSelected(ProjectMode.TRANSLATION) }
        )
        TranslationTypeCard(
            title = Res.string.narration,
            description = Res.string.narrationDesc,
            onClick = { onModeSelected(ProjectMode.NARRATION) }
        )
        TranslationTypeCard(
            title = Res.string.dialect,
            description = Res.string.dialectDesc,
            onClick = { onModeSelected(ProjectMode.DIALECT) }
        )
    }
}

@Composable
private fun TranslationTypeCard(
    title: StringResource,
    description: StringResource,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A searchable language table (ported from the JVM `languageTableView`): columns are
 * Language (name) | Anglicized | Code (slug). Clicking a row selects it, firing the
 * equivalent of the JVM app's LanguageSelectedEvent.
 */
@Composable
private fun LanguageTable(
    languages: List<Language>,
    onLanguageSelected: (Language) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeaderCell(Res.string.language, 0.4f)
                    HeaderCell(Res.string.anglicized, 0.4f)
                    HeaderCell(Res.string.code, 0.2f)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            items(languages, key = { it.slug }) { lang ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onLanguageSelected(lang) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lang.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.4f)
                    )
                    Text(
                        text = lang.anglicizedName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.4f)
                    )
                    Text(
                        text = lang.slug,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.2f)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

/**
 * A resource-version table for wizard step 4 (ported from the JVM `resourceVersionTableView`).
 * Clicking a row fires the equivalent of ResourceVersionSelectedEvent and creates the project.
 */
@Composable
private fun ResourceVersionTable(
    versions: List<OratureResourceVersion>,
    onVersionSelected: (OratureResourceVersion) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(versions, key = { it.slug }) { version ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onVersionSelected(version) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = version.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = version.slug.uppercase(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(
    label: StringResource,
    weight: Float
) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(weight)
    )
}
