package org.bibletranslationtools.orature.plugins

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
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

    private val directoryProvider: IDirectoryProvider by inject()
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

    companion object {
        const val NO_ID = -1
        private const val PLUGINS_FILE = "orature-plugins.json"
    }
}
