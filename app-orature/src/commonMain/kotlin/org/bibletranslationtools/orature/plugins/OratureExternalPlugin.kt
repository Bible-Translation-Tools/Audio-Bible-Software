package org.bibletranslationtools.orature.plugins

import kotlinx.serialization.Serializable

/**
 * A registered external audio editor (JVM: AudioPluginData for a non-native plugin). Orature's native
 * (embedded-JavaFX) plugins can't be ported to Compose Multiplatform, so only external editors are
 * supported: an executable launched as a separate process on a take's WAV file.
 *
 * [args] is the command-line template; the token [WAV_TOKEN] is replaced with the audio file path at
 * launch (e.g. ["--edit", "${'$'}{wav}"]). If no token is present the file path is appended.
 */
@Serializable
data class OratureExternalPlugin(
    val id: Int,
    val name: String,
    val executable: String,
    val args: List<String> = emptyList(),
    val canEdit: Boolean = true,
    val canRecord: Boolean = false,
    val canMark: Boolean = false
) {
    companion object {
        const val WAV_TOKEN = "\${wav}"

        /** Reserved id for Orature's built-in (native) recorder/editor. It's always listed and can't
         *  be removed; selecting it for a role means that role uses native capture/editing (JVM: the
         *  built-in "OratureRecorder" plugin, which isNativePlugin() == true). */
        const val BUILTIN_ID = 0

        /** The permanent built-in entry shown in the plugin list. */
        val BUILTIN = OratureExternalPlugin(
            id = BUILTIN_ID,
            name = "OratureRecorder",
            executable = "",
            canEdit = true,
            canRecord = true,
            canMark = true
        )
    }
}

/** Which role a plugin fills when launched (JVM: PluginType). */
enum class OraturePluginType { RECORDER, EDITOR, MARKER }
