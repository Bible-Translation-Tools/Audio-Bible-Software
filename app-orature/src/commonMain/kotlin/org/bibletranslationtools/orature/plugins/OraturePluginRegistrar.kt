package org.bibletranslationtools.orature.plugins

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

/**
 * A plugin definition file (JVM: ParsedAudioPluginData) — the YAML format Orature uses to declare an
 * external audio app: name/version, capability flags, per-OS executable candidates, and an args
 * template. Example:
 * ```yaml
 * name: Audacity
 * canEdit: true
 * canRecord: true
 * executable:
 *   macos: [ /Applications/Audacity.app ]
 *   windows: [ "C:\\Program Files\\Audacity\\audacity.exe" ]
 *   linux: [ audacity ]
 * args: []
 * ```
 */
data class OraturePluginDefinition(
    val name: String = "",
    val version: String = "",
    val canEdit: Boolean = false,
    val canRecord: Boolean = false,
    val canMark: Boolean = false,
    val executable: OratureExecutable = OratureExecutable(),
    val args: List<String> = emptyList()
)

data class OratureExecutable(
    val macos: List<String>? = null,
    val windows: List<String>? = null,
    val linux: List<String>? = null
)

/**
 * Parses Orature plugin-definition YAML files into [OratureExternalPlugin]s, resolving the executable
 * for the current OS (JVM: AudioPluginRegistrar + ParsedAudioPluginDataMapper): picks the per-OS
 * candidate list, substitutes `${'$'}{user.name}`, resolves a macOS `.app` to its inner binary, and
 * chooses the first path that exists and is executable.
 */
class OraturePluginRegistrar {

    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    /** Parse a single definition file, or null if unreadable / no valid executable on this OS. */
    fun parse(yamlFile: File): OratureExternalPlugin? {
        val def = runCatching { mapper.readValue<OraturePluginDefinition>(yamlFile) }
            .getOrElse { System.err.println("Invalid plugin definition ${yamlFile.name}: $it"); return null }
        if (def.name.isBlank()) return null
        val executable = selectExecutable(def) ?: return null
        return OratureExternalPlugin(
            id = 0,
            name = def.name,
            executable = executable,
            args = def.args,
            canEdit = def.canEdit,
            canRecord = def.canRecord,
            canMark = def.canMark
        )
    }

    /** Parse every `*.yaml` in [dir] (JVM: importAll). */
    fun parseAll(dir: File): List<OratureExternalPlugin> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".yaml") }
            ?.mapNotNull { parse(it) }
            ?: emptyList()

    private fun selectExecutable(def: OraturePluginDefinition): String? {
        val os = System.getProperty("os.name").orEmpty().uppercase()
        val candidates = when {
            os.contains("WIN") -> def.executable.windows
            os.contains("MAC") -> def.executable.macos
            else -> def.executable.linux
        } ?: return null
        val user = System.getProperty("user.name").orEmpty()
        return candidates
            .map { resolvePluginExecutable(it.replace("\${user.name}", user)) }
            .firstOrNull { val f = File(it); f.exists() && f.canExecute() }
    }
}
