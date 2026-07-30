package org.bibletranslationtools.bttrecorder2.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.bttrecorder2.ui.TestTags
import org.bibletranslationtools.shared.preferences.AppSettings
import org.bibletranslationtools.shared.preferences.ThemeMode
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.LangNamesUpdateState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SettingsViewModel
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_back
import org.bibletranslationtools.shared.resources.settings_app_language
import org.bibletranslationtools.shared.resources.settings_input_device
import org.bibletranslationtools.shared.resources.settings_lang_names_error
import org.bibletranslationtools.shared.resources.settings_lang_names_success
import org.bibletranslationtools.shared.resources.settings_lang_names_updating
import org.bibletranslationtools.shared.resources.settings_language_note
import org.bibletranslationtools.shared.resources.settings_no_devices
import org.bibletranslationtools.shared.resources.settings_output_device
import org.bibletranslationtools.shared.resources.settings_section_appearance
import org.bibletranslationtools.shared.resources.settings_section_audio
import org.bibletranslationtools.shared.resources.settings_section_language
import org.bibletranslationtools.shared.resources.settings_system_default
import org.bibletranslationtools.shared.resources.settings_theme
import org.bibletranslationtools.shared.resources.settings_theme_dark
import org.bibletranslationtools.shared.resources.settings_theme_light
import org.bibletranslationtools.shared.resources.settings_theme_system
import org.bibletranslationtools.shared.resources.settings_title
import org.bibletranslationtools.shared.resources.settings_lang_names_reset
import org.bibletranslationtools.shared.resources.settings_lang_names_url
import org.bibletranslationtools.shared.resources.settings_update_language_names
import org.bibletranslationtools.shared.resources.settings_update_language_names_desc

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
    onBackClick: () -> Unit
) {
    val ui by viewModel.uiState.collectAsState()

    // Re-scan hardware when the screen is shown so hot-plugged devices appear.
    LaunchedEffect(Unit) { viewModel.loadDevices() }

    Scaffold(
        modifier = Modifier.testTag(TestTags.SETTINGS_SCREEN),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Audio ──────────────────────────────────────────────────────
            SettingsSection(title = stringResource(Res.string.settings_section_audio)) {
                val systemDefault = stringResource(Res.string.settings_system_default)
                val noDevices = stringResource(Res.string.settings_no_devices)

                DropdownSetting(
                    label = stringResource(Res.string.settings_output_device),
                    options = ui.outputDevices.map { it.id to it.name },
                    selectedId = ui.selectedOutputId,
                    emptyText = noDevices,
                    placeholder = systemDefault,
                    onSelect = { id ->
                        ui.outputDevices.firstOrNull { it.id == id }?.let(viewModel::selectOutputDevice)
                    }
                )
                DropdownSetting(
                    label = stringResource(Res.string.settings_input_device),
                    options = ui.inputDevices.map { it.id to it.name },
                    selectedId = ui.selectedInputId,
                    emptyText = noDevices,
                    placeholder = systemDefault,
                    onSelect = { id ->
                        ui.inputDevices.firstOrNull { it.id == id }?.let(viewModel::selectInputDevice)
                    }
                )
            }

            HorizontalDivider()

            // ── Appearance (theme) ─────────────────────────────────────────
            SettingsSection(title = stringResource(Res.string.settings_section_appearance)) {
                Text(
                    text = stringResource(Res.string.settings_theme),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                val themeLabels = listOf(
                    ThemeMode.SYSTEM to stringResource(Res.string.settings_theme_system),
                    ThemeMode.LIGHT to stringResource(Res.string.settings_theme_light),
                    ThemeMode.DARK to stringResource(Res.string.settings_theme_dark)
                )
                themeLabels.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = ui.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = ui.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) }
                        )
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            HorizontalDivider()

            // ── Language ───────────────────────────────────────────────────
            SettingsSection(title = stringResource(Res.string.settings_section_language)) {
                val current = ui.languageOptions.firstOrNull { it.tag == ui.appLanguageTag }
                    ?: ui.languageOptions.firstOrNull()
                DropdownSetting(
                    label = stringResource(Res.string.settings_app_language),
                    options = ui.languageOptions.map { (it.tag ?: "") to it.displayName },
                    selectedId = current?.tag ?: "",
                    emptyText = "",
                    placeholder = "",
                    onSelect = { id -> viewModel.setAppLanguage(id.ifBlank { null }) }
                )
                Text(
                    text = stringResource(Res.string.settings_language_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(12.dp))

                // URL field — persisted to preferences, used by the update button below.
                var urlFieldValue by remember(ui.langNamesUrl) { mutableStateOf(ui.langNamesUrl) }
                val langUpdateState = ui.langNamesUpdateState
                val isCustomUrl = urlFieldValue != AppSettings.DEFAULT_LANG_NAMES_URL
                OutlinedTextField(
                    value = urlFieldValue,
                    onValueChange = { urlFieldValue = it },
                    label = { Text(stringResource(Res.string.settings_lang_names_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { viewModel.setLangNamesUrl(urlFieldValue) }
                    ),
                    enabled = langUpdateState !is LangNamesUpdateState.InProgress,
                    trailingIcon = {
                        if (isCustomUrl) {
                            IconButton(onClick = {
                                urlFieldValue = AppSettings.DEFAULT_LANG_NAMES_URL
                                viewModel.setLangNamesUrl(AppSettings.DEFAULT_LANG_NAMES_URL)
                            }) {
                                Icon(
                                    Icons.Default.SettingsBackupRestore,
                                    contentDescription = stringResource(Res.string.settings_lang_names_reset)
                                )
                            }
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))

                // Update language names from remote
                Text(
                    text = stringResource(Res.string.settings_update_language_names_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            // Persist any unsaved URL edit before fetching.
                            viewModel.setLangNamesUrl(urlFieldValue)
                            viewModel.updateLanguageNames()
                        },
                        enabled = langUpdateState !is LangNamesUpdateState.InProgress
                    ) {
                        if (langUpdateState is LangNamesUpdateState.InProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    when (langUpdateState) {
                        is LangNamesUpdateState.InProgress ->
                            Text(
                                text = stringResource(Res.string.settings_lang_names_updating),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        is LangNamesUpdateState.Success ->
                            Text(
                                text = stringResource(Res.string.settings_lang_names_success),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        is LangNamesUpdateState.Error ->
                            Text(
                                text = stringResource(Res.string.settings_lang_names_error, langUpdateState.message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

/**
 * A labeled value that opens a dropdown of [options] (id → display name) when
 * tapped. [selectedId] highlights the current value; [placeholder] shows when
 * nothing is selected; [emptyText] shows when there are no options.
 */
@Composable
private fun DropdownSetting(
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    emptyText: String,
    placeholder: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.first == selectedId }?.second
        ?: placeholder.takeIf { it.isNotEmpty() }
        ?: emptyText

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = options.isNotEmpty()) { expanded = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (options.isEmpty()) emptyText else selectedName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            onSelect(id)
                        }
                    )
                }
            }
        }
    }
}
