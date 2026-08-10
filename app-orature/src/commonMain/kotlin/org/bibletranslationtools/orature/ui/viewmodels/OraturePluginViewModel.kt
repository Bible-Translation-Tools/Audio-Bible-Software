package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bibletranslationtools.orature.plugins.OratureExternalPlugin
import org.bibletranslationtools.orature.plugins.OraturePluginRegistrar
import org.bibletranslationtools.orature.plugins.OraturePluginStore
import org.bibletranslationtools.orature.plugins.canLaunchPlugins
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

data class OraturePluginUiState(
    val plugins: List<OratureExternalPlugin> = emptyList(),
    val selectedEditorId: Int = OraturePluginStore.NO_ID,
    val selectedRecorderId: Int = OraturePluginStore.NO_ID,
    val selectedMarkerId: Int = OraturePluginStore.NO_ID,
    /** False on Android (no external process launch); the plugins section is hidden there. */
    val supported: Boolean = true
)

/**
 * Manages the registered external-editor plugins for the App Settings screen (JVM: AudioPluginViewModel
 * + AddPluginViewModel): list, add, remove, and choose which plugin is the default editor/recorder.
 * Backed by [OraturePluginStore] (a JSON file).
 */
class OraturePluginViewModel : ViewModel(), KoinComponent {

    private val store: OraturePluginStore by inject()
    private val directoryProvider: IAppDirectories by inject()
    private val registrar = OraturePluginRegistrar()

    private val _uiState = MutableStateFlow(OraturePluginUiState(supported = canLaunchPlugins()))
    val uiState: StateFlow<OraturePluginUiState> = _uiState.asStateFlow()

    init {
        importDroppedInDefinitions()
        reload()
    }

    private fun pluginsDir(): File = directoryProvider.getAppDataDirectory("plugins")

    /** Register any *.yaml plugin definitions dropped into the plugins dir that aren't already
     *  registered (JVM: AudioPluginRegistrar.importAll on startup). */
    private fun importDroppedInDefinitions() {
        if (!canLaunchPlugins()) return
        val dir = pluginsDir()
        if (!dir.isDirectory) return
        val reg = store.load()
        val existingNames = reg.plugins.map { it.name }.toSet()
        val discovered = registrar.parseAll(dir).filter { it.name !in existingNames }
        if (discovered.isEmpty()) return
        var nextId = reg.plugins.maxOfOrNull { it.id } ?: 0
        val added = discovered.map { it.copy(id = ++nextId) }
        store.save(reg.copy(plugins = reg.plugins + added))
    }

    /** Import a plugin definition YAML the user picked (JVM: AudioPluginRegistrar.import). */
    fun importDefinition(yamlPath: String) {
        val plugin = registrar.parse(File(yamlPath)) ?: return
        addPlugin(plugin.name, plugin.executable, plugin.args, plugin.canEdit, plugin.canRecord, plugin.canMark)
    }

    private fun reload() {
        val reg = store.load()
        _uiState.value = _uiState.value.copy(
            plugins = reg.plugins,
            selectedEditorId = reg.selectedEditorId,
            selectedRecorderId = reg.selectedRecorderId,
            selectedMarkerId = reg.selectedMarkerId
        )
    }

    private fun persist(
        plugins: List<OratureExternalPlugin>,
        editorId: Int = _uiState.value.selectedEditorId,
        recorderId: Int = _uiState.value.selectedRecorderId,
        markerId: Int = _uiState.value.selectedMarkerId
    ) {
        store.save(OraturePluginStore.Registry(plugins, editorId, recorderId, markerId))
        _uiState.value = _uiState.value.copy(
            plugins = plugins,
            selectedEditorId = editorId,
            selectedRecorderId = recorderId,
            selectedMarkerId = markerId
        )
    }

    /** Register a new external editor (JVM: CreatePlugin). Auto-selects it as the default for the
     *  role(s) it fills if none is chosen yet. */
    fun addPlugin(name: String, executable: String, args: List<String>, canEdit: Boolean, canRecord: Boolean, canMark: Boolean) {
        if (name.isBlank() || executable.isBlank()) return
        val current = _uiState.value
        val nextId = (current.plugins.maxOfOrNull { it.id } ?: 0) + 1
        // Resolve a macOS .app bundle to its inner binary at add time, matching Orature's
        // completePluginPath (so the stored executable is runnable, not a directory).
        val resolved = org.bibletranslationtools.orature.plugins.resolvePluginExecutable(executable.trim())
        val plugin = OratureExternalPlugin(nextId, name.trim(), resolved, args, canEdit, canRecord, canMark)
        val editorId = if (canEdit && current.selectedEditorId == OraturePluginStore.NO_ID) nextId else current.selectedEditorId
        val recorderId = if (canRecord && current.selectedRecorderId == OraturePluginStore.NO_ID) nextId else current.selectedRecorderId
        val markerId = if (canMark && current.selectedMarkerId == OraturePluginStore.NO_ID) nextId else current.selectedMarkerId
        persist(current.plugins + plugin, editorId, recorderId, markerId)
    }

    fun removePlugin(id: Int) {
        if (id == OratureExternalPlugin.BUILTIN_ID) return // the built-in recorder can't be removed
        val current = _uiState.value
        // Removing the selected plugin falls back to the built-in recorder/editor (JVM: OratureRecorder
        // is always available), rather than leaving the role unset.
        val builtin = OratureExternalPlugin.BUILTIN_ID
        val editorId = if (current.selectedEditorId == id) builtin else current.selectedEditorId
        val recorderId = if (current.selectedRecorderId == id) builtin else current.selectedRecorderId
        val markerId = if (current.selectedMarkerId == id) builtin else current.selectedMarkerId
        persist(current.plugins.filterNot { it.id == id }, editorId, recorderId, markerId)
    }

    fun selectEditor(id: Int) = persist(_uiState.value.plugins, editorId = id)
    fun selectRecorder(id: Int) = persist(_uiState.value.plugins, recorderId = id)
    fun selectMarker(id: Int) = persist(_uiState.value.plugins, markerId = id)
}
