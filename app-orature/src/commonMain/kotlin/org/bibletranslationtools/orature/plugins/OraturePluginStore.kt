package org.bibletranslationtools.orature.plugins

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * Persists the registered external editors + the currently-selected editor/recorder (JVM:
 * AudioPluginRepository over the audio_plugin_entity table + preferences). The port stores them in a
 * small JSON file in the app data directory instead of the SQLite table — the storage mechanism is
 * invisible to the user, and this avoids wiring a new DAO/preferences across both platforms for a
 * desktop-only feature. Registered as a Koin single.
 */
class OraturePluginStore : KoinComponent {

    private val directoryProvider: IAppDirectories by inject()
    private val mapper = jacksonObjectMapper()

    private val file: File
        get() = File(directoryProvider.getAppDataDirectory(), PLUGINS_FILE)

    data class Registry(
        val plugins: List<OratureExternalPlugin> = emptyList(),
        val selectedEditorId: Int = NO_ID,
        val selectedRecorderId: Int = NO_ID,
        val selectedMarkerId: Int = NO_ID
    )

    fun load(): Registry = runCatching {
        val f = file
        if (f.exists() && f.length() > 0) mapper.readValue<Registry>(f) else Registry()
    }.getOrDefault(Registry())

    fun save(registry: Registry) {
        runCatching {
            file.parentFile?.mkdirs()
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, registry)
        }.onFailure { System.err.println("Failed to save plugins: $it") }
    }

    /**
     * The plugin currently selected for [capability], or null if external plugins are unavailable
     * on this platform, none is selected, or the selected one cannot do the job.
     *
     * Selecting a plugin used to be open-coded in every screen that launched one — narration
     * (editor and marker), blind draft (editor and recorder), peer edit, and chapter review each
     * repeated the same three steps, six copies in four files. The capability check is the part
     * that is easy to get wrong: a registry can name a `selectedEditorId` whose plugin has since
     * been re-registered without `canEdit`, and returning it anyway launches a plugin that cannot
     * do what the caller wants.
     */
    fun selected(capability: PluginCapability): OratureExternalPlugin? {
        if (!canLaunchPlugins()) return null
        val registry = load()
        val id = when (capability) {
            PluginCapability.EDIT -> registry.selectedEditorId
            PluginCapability.RECORD -> registry.selectedRecorderId
            PluginCapability.MARK -> registry.selectedMarkerId
        }
        return registry.plugins.firstOrNull { it.id == id && capability.isSupportedBy(it) }
    }

    companion object {
        const val NO_ID = -1
        private const val PLUGINS_FILE = "orature-plugins.json"
    }
}

/** What a caller needs a plugin to be able to do. */
enum class PluginCapability {
    EDIT,
    RECORD,
    MARK;

    fun isSupportedBy(plugin: OratureExternalPlugin): Boolean = when (this) {
        EDIT -> plugin.canEdit
        RECORD -> plugin.canRecord
        MARK -> plugin.canMark
    }
}
