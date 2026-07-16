package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureLangNamesUpdateState
import org.bibletranslationtools.orature.ui.viewmodels.OratureSettingsViewModel
import org.bibletranslationtools.shared.preferences.AppSettings
import org.bibletranslationtools.shared.preferences.ThemeMode
import io.github.vinceglb.filekit.path
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.action
import org.bibletranslationtools.orature.resources.addApp
import org.bibletranslationtools.orature.resources.addVerseMarker
import org.bibletranslationtools.orature.resources.appSettings
import org.bibletranslationtools.orature.resources.audioSettings
import org.bibletranslationtools.orature.resources.browse
import org.bibletranslationtools.orature.resources.checkForUpdates
import org.bibletranslationtools.orature.resources.close
import org.bibletranslationtools.orature.resources.colorTheme
import org.bibletranslationtools.orature.resources.dark
import org.bibletranslationtools.orature.resources.delete
import org.bibletranslationtools.orature.resources.edit
import org.bibletranslationtools.orature.resources.editVerseMarkers
import org.bibletranslationtools.orature.resources.focus
import org.bibletranslationtools.orature.resources.goBack
import org.bibletranslationtools.orature.resources.interfaceSettings
import org.bibletranslationtools.orature.resources.keyboardShortcutsSettings
import org.bibletranslationtools.orature.resources.language
import org.bibletranslationtools.orature.resources.languageSettings
import org.bibletranslationtools.orature.resources.languagesImportError
import org.bibletranslationtools.orature.resources.languagesImportSuccess
import org.bibletranslationtools.orature.resources.light
import org.bibletranslationtools.orature.resources.location
import org.bibletranslationtools.orature.resources.navigation
import org.bibletranslationtools.orature.resources.noAppsConfigured
import org.bibletranslationtools.orature.resources.noDevicesFound
import org.bibletranslationtools.orature.resources.playPauseSource
import org.bibletranslationtools.orature.resources.playPauseTarget
import org.bibletranslationtools.orature.resources.playbackSettings
import org.bibletranslationtools.orature.resources.record
import org.bibletranslationtools.orature.resources.recordSettings
import org.bibletranslationtools.orature.resources.recordStop
import org.bibletranslationtools.orature.resources.reset
import org.bibletranslationtools.orature.resources.scrollDown
import org.bibletranslationtools.orature.resources.scrollUp
import org.bibletranslationtools.orature.resources.select
import org.bibletranslationtools.orature.resources.settings
import org.bibletranslationtools.orature.resources.shortcut
import org.bibletranslationtools.orature.resources.system
import org.bibletranslationtools.orature.resources.systemDefault
import org.bibletranslationtools.orature.resources.updatingWait
import org.bibletranslationtools.orature.resources.useInternetWarning

/**
 * Orature's settings drawer: a scrollable 550dp panel matching the real JVM app's
 * SettingsView. Panel background is [MaterialTheme.colorScheme.surface] (the -wa-foreground
 * white/dark), 40dp content padding, ~30dp between sections. Sections in SettingsView order:
 *  - header (title + close ✕)
 *  - Interface Settings: Color Theme + UI Language dropdowns (icon-led)
 *  - Audio Settings: Playback (output) + Record (input) device dropdowns (icon-led)
 *  - Language Settings: FUNCTIONAL langnames-URL updater (via ImportLanguages)
 *  - App Settings: external audio plugins — VISUAL only (Phase 12 wires the real registry)
 *  - Keyboard Shortcuts: static reference rows mirroring the JVM KeyboardShortcuts grid
 */
@Composable
fun OratureSettingsDrawer(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OratureSettingsViewModel = viewModel { OratureSettingsViewModel() }
) {
    val ui by viewModel.uiState.collectAsState()

    // Re-scan hardware whenever the drawer is shown so hot-plugged devices appear.
    LaunchedEffect(Unit) { viewModel.loadDevices() }

    val themeLabels = listOf(
        ThemeMode.SYSTEM to stringResource(Res.string.system),
        ThemeMode.LIGHT to stringResource(Res.string.light),
        ThemeMode.DARK to stringResource(Res.string.dark)
    )
    val systemDefaultText = stringResource(Res.string.systemDefault)
    val noDevices = stringResource(Res.string.noDevicesFound)

    Surface(
        modifier = modifier.width(550.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.settings),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.close),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // ── Interface Settings ──────────────────────────────────────────
            SectionTitle(stringResource(Res.string.interfaceSettings))
            Section {
                OratureDropdown(
                    label = stringResource(Res.string.colorTheme),
                    leadingIcon = Icons.Filled.Brightness6,
                    options = themeLabels.map { it.first.name to it.second },
                    selectedId = ui.themeMode.name,
                    placeholder = "",
                    emptyText = "",
                    onSelect = { id -> viewModel.setThemeMode(ThemeMode.valueOf(id)) }
                )

                val currentLang = ui.languageOptions.firstOrNull { it.tag == ui.appLanguageTag }
                    ?: ui.languageOptions.firstOrNull()
                OratureDropdown(
                    label = stringResource(Res.string.language),
                    leadingIcon = Icons.Filled.Public,
                    options = ui.languageOptions.map { (it.tag ?: "") to it.displayName },
                    selectedId = currentLang?.tag ?: "",
                    placeholder = systemDefaultText,
                    emptyText = systemDefaultText,
                    onSelect = { id -> viewModel.setAppLanguage(id.ifBlank { null }) }
                )
            }

            // ── Audio Settings ──────────────────────────────────────────────
            SectionTitle(stringResource(Res.string.audioSettings))
            Section {
                OratureDropdown(
                    label = stringResource(Res.string.playbackSettings),
                    leadingIcon = Icons.Filled.VolumeUp,
                    options = ui.outputDevices.map { it.id to it.name },
                    selectedId = ui.selectedOutputId,
                    placeholder = systemDefaultText,
                    emptyText = noDevices,
                    onSelect = { id ->
                        ui.outputDevices.firstOrNull { it.id == id }?.let(viewModel::selectOutputDevice)
                    }
                )
                OratureDropdown(
                    label = stringResource(Res.string.recordSettings),
                    leadingIcon = Icons.Filled.Mic,
                    options = ui.inputDevices.map { it.id to it.name },
                    selectedId = ui.selectedInputId,
                    placeholder = systemDefaultText,
                    emptyText = noDevices,
                    onSelect = { id ->
                        ui.inputDevices.firstOrNull { it.id == id }?.let(viewModel::selectInputDevice)
                    }
                )
            }

            // ── Language Settings (langnames updater — functional) ──────────
            SectionTitle(stringResource(Res.string.languageSettings))
            LanguageNamesSection(
                url = ui.langNamesUrl,
                updateState = ui.langNamesUpdateState,
                onSetUrl = viewModel::setLangNamesUrl,
                onCheckForUpdates = viewModel::updateLanguageNames,
                onReset = viewModel::resetLangNamesUrl
            )

            // ── App Settings (external editor plugins) — desktop only (process launch) ──
            if (org.bibletranslationtools.orature.plugins.canLaunchPlugins()) {
                SectionTitle(stringResource(Res.string.appSettings))
                AppPluginsSection()
            }

            // ── Keyboard Shortcuts (static reference) ───────────────────────
            SectionTitle(stringResource(Res.string.keyboardShortcutsSettings))
            KeyboardShortcutsSection()
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { content() }
}

/**
 * A labeled Material3 exposed dropdown with a leading icon on the selected value (matching
 * SettingsView's IconComboBoxCell). [options] are id → display-name pairs; [selectedId]
 * picks the current value, [placeholder] shows when nothing matches, [emptyText] shows
 * (disabled) when there are no options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OratureDropdown(
    label: String,
    leadingIcon: ImageVector,
    options: List<Pair<String, String>>,
    selectedId: String?,
    placeholder: String,
    emptyText: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasOptions = options.isNotEmpty()
    val selectedName = options.firstOrNull { it.first == selectedId }?.second
        ?: placeholder.takeIf { it.isNotEmpty() }
        ?: emptyText

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = expanded && hasOptions,
            onExpandedChange = { if (hasOptions) expanded = it }
        ) {
            OutlinedTextField(
                value = if (hasOptions) selectedName else emptyText,
                onValueChange = {},
                readOnly = true,
                enabled = hasOptions,
                leadingIcon = { Icon(leadingIcon, contentDescription = null) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && hasOptions)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(
                expanded = expanded && hasOptions,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (id, name) ->
                    androidx.compose.material3.DropdownMenuItem(
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

/**
 * Functional langnames-URL updater. A warning/help line, a bound URL field, a primary
 * "Check for updates" button (with progress + result text) and a "Reset" button that
 * restores the default URL. Mirrors SettingsView's Language Settings block.
 */
@Composable
private fun LanguageNamesSection(
    url: String,
    updateState: OratureLangNamesUpdateState,
    onSetUrl: (String) -> Unit,
    onCheckForUpdates: () -> Unit,
    onReset: () -> Unit
) {
    Section {
        Text(
            text = stringResource(Res.string.useInternetWarning),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Local edit buffer synced to the persisted URL; committed on the button actions.
        var urlField by remember(url) { mutableStateOf(url) }
        val inProgress = updateState is OratureLangNamesUpdateState.InProgress
        val isCustom = urlField != AppSettings.DEFAULT_LANG_NAMES_URL

        Column {
            Text(
                text = stringResource(Res.string.location),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urlField,
                onValueChange = { urlField = it },
                singleLine = true,
                enabled = !inProgress,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (isCustom) {
                        IconButton(onClick = {
                            urlField = AppSettings.DEFAULT_LANG_NAMES_URL
                            onSetUrl(AppSettings.DEFAULT_LANG_NAMES_URL)
                        }) {
                            Icon(
                                Icons.Filled.SettingsBackupRestore,
                                contentDescription = stringResource(Res.string.reset)
                            )
                        }
                    }
                }
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    onSetUrl(urlField)
                    onCheckForUpdates()
                },
                enabled = !inProgress
            ) {
                if (inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(Res.string.checkForUpdates))
            }
            if (isCustom && !inProgress) {
                OutlinedButton(onClick = {
                    urlField = AppSettings.DEFAULT_LANG_NAMES_URL
                    onReset()
                }) {
                    Text(stringResource(Res.string.reset))
                }
            }
        }

        when (updateState) {
            is OratureLangNamesUpdateState.InProgress -> Text(
                text = stringResource(Res.string.updatingWait),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is OratureLangNamesUpdateState.Success -> Text(
                text = stringResource(Res.string.languagesImportSuccess),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary
            )
            is OratureLangNamesUpdateState.Error -> Text(
                text = stringResource(Res.string.languagesImportError),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.error
            )
            OratureLangNamesUpdateState.Idle -> Unit
        }
    }
}

/**
 * External audio-plugin registry — VISUAL placeholder only. Phase 12 wires the real
 * plugin registry (add/select recorder + editor apps). Mirrors SettingsView's App Settings
 * block: a header row with right-aligned Record/Edit column icons, an (empty) plugin list,
 * and an "Add App" link.
 */
@Composable
private fun AppPluginsSection() {
    val pluginVm: org.bibletranslationtools.orature.ui.viewmodels.OraturePluginViewModel =
        viewModel { org.bibletranslationtools.orature.ui.viewmodels.OraturePluginViewModel() }
    val ui by pluginVm.uiState.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    // Import an Orature plugin-definition YAML the user picks (JVM: AudioPluginRegistrar.import).
    val yamlPicker = io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher(
        type = io.github.vinceglb.filekit.dialogs.FileKitType.File(extensions = listOf("yaml", "yml")),
        mode = io.github.vinceglb.filekit.dialogs.FileKitMode.Single
    ) { file -> file?.let { pluginVm.importDefinition(it.path) } }

    Section {
        // Header row: name column (flex) + right-aligned record/edit icon columns.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Column(modifier = Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Mic, contentDescription = stringResource(Res.string.record), tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(modifier = Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.edit), tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(modifier = Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmark, contentDescription = stringResource(Res.string.editVerseMarkers), tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(40.dp))
        }

        // The built-in "OratureRecorder" is always listed and can't be removed (JVM). It's the
        // selected role whenever no external plugin is chosen for that role.
        val builtin = org.bibletranslationtools.orature.plugins.OratureExternalPlugin.BUILTIN
        val builtinRecorderSelected = ui.plugins.none { it.id == ui.selectedRecorderId }
        val builtinEditorSelected = ui.plugins.none { it.id == ui.selectedEditorId }
        val builtinMarkerSelected = ui.plugins.none { it.id == ui.selectedMarkerId }
        PluginRow(
            name = builtin.name,
            showRecord = true, recordSelected = builtinRecorderSelected, onRecord = { pluginVm.selectRecorder(builtin.id) },
            showEdit = true, editSelected = builtinEditorSelected, onEdit = { pluginVm.selectEditor(builtin.id) },
            showMark = true, markSelected = builtinMarkerSelected, onMark = { pluginVm.selectMarker(builtin.id) },
            onRemove = null // non-removable
        )
        for (plugin in ui.plugins) {
            PluginRow(
                name = plugin.name,
                showRecord = plugin.canRecord, recordSelected = ui.selectedRecorderId == plugin.id, onRecord = { pluginVm.selectRecorder(plugin.id) },
                showEdit = plugin.canEdit, editSelected = ui.selectedEditorId == plugin.id, onEdit = { pluginVm.selectEditor(plugin.id) },
                showMark = plugin.canMark, markSelected = ui.selectedMarkerId == plugin.id, onMark = { pluginVm.selectMarker(plugin.id) },
                onRemove = { pluginVm.removePlugin(plugin.id) }
            )
        }

        // "Add App" (manual) + "Import" (a plugin-definition YAML).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.addApp),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { yamlPicker.launch() }) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.browse),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showAdd) {
        OratureAddPluginDialog(
            onAdd = { name, exec, canEdit, canRecord, canMark -> pluginVm.addPlugin(name, exec, emptyList(), canEdit, canRecord, canMark) },
            onDismiss = { showAdd = false }
        )
    }
}

/** One row of the plugin registry: name + record/edit/mark role selectors + optional remove. */
@Composable
private fun PluginRow(
    name: String,
    showRecord: Boolean, recordSelected: Boolean, onRecord: () -> Unit,
    showEdit: Boolean, editSelected: Boolean, onEdit: () -> Unit,
    showMark: Boolean, markSelected: Boolean, onMark: () -> Unit,
    onRemove: (() -> Unit)?
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Column(modifier = Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showRecord) androidx.compose.material3.RadioButton(selected = recordSelected, onClick = onRecord)
        }
        Column(modifier = Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showEdit) androidx.compose.material3.RadioButton(selected = editSelected, onClick = onEdit)
        }
        Column(modifier = Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showMark) androidx.compose.material3.RadioButton(selected = markSelected, onClick = onMark)
        }
        Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            if (onRemove != null) {
                androidx.compose.material3.IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Static keyboard-shortcut reference, reproducing the JVM KeyboardShortcuts grid: an
 * Action / Shortcut header, then one row per shortcut with the key combo(s) rendered as
 * bordered chips. The combos are representative defaults; the JVM app derives them from a
 * Shortcut registry not yet ported (TODO: wire to the real registry when it lands).
 */
@Composable
private fun KeyboardShortcutsSection() {
    val rows = listOf(
        stringResource(Res.string.focus) to listOf("Tab"),
        stringResource(Res.string.select) to listOf("Space", "Enter"),
        stringResource(Res.string.navigation) to listOf("↑", "↓", "←", "→"),
        stringResource(Res.string.scrollDown) to listOf("PgDn", "↓"),
        stringResource(Res.string.scrollUp) to listOf("PgUp", "↑"),
        stringResource(Res.string.goBack) to listOf("Esc"),
        stringResource(Res.string.addVerseMarker) to listOf("M"),
        stringResource(Res.string.recordStop) to listOf("R"),
        stringResource(Res.string.playPauseSource) to listOf("Ctrl", "1"),
        stringResource(Res.string.playPauseTarget) to listOf("Ctrl", "2")
    )

    Section {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(Res.string.action),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(Res.string.shortcut),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        rows.forEach { (action, keys) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = action,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    keys.forEach { key -> ShortcutChip(key) }
                }
            }
        }
    }
}

@Composable
private fun ShortcutChip(text: String) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
