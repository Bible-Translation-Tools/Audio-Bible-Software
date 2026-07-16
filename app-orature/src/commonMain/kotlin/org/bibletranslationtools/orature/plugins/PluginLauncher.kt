package org.bibletranslationtools.orature.plugins

import org.bibletranslationtools.otter.common.domain.plugins.PluginParameters
import java.io.File

/** True when external plugins can be launched (desktop only — process launching). */
expect fun canLaunchPlugins(): Boolean

/**
 * Resolve a user-picked or declared executable path to something runnable. On macOS a picked `.app`
 * bundle is a directory, so it's resolved to its inner binary `<App>.app/Contents/MacOS/<exe>` (JVM:
 * AddPluginViewModel.completePluginPath). Identity elsewhere.
 */
expect fun resolvePluginExecutable(path: String): String

/** Run [command] as a process, blocking until it exits; true on exit code 0. No-op false on Android. */
expect suspend fun runPluginProcess(command: List<String>): Boolean

/**
 * Launch [plugin] on [audioFile] with the translation [params], blocking until it exits (JVM:
 * AudioPlugin.launch → runProcess). The plugin's [OratureExternalPlugin.args] is a template of
 * placeholder tokens; see [buildPluginArgs]. Returns true if the process ran and exited 0.
 */
suspend fun launchPlugin(
    plugin: OratureExternalPlugin,
    audioFile: File,
    params: PluginParameters? = null
): Boolean {
    val command = listOf(resolvePluginExecutable(plugin.executable)) + buildPluginArgs(plugin.args, audioFile, params)
    return runPluginProcess(command)
}

/**
 * Substitute the plugin's arg template into concrete process arguments. Supported tokens map to the
 * translation context (JVM: AudioPlugin.buildJarArguments placeholder set). Unknown tokens are
 * dropped. An empty template means "just the audio file path" — the default for a dumb editor that
 * only knows how to open a WAV (JVM: buildBinArguments default).
 */
fun buildPluginArgs(
    template: List<String>,
    audioFile: File,
    params: PluginParameters?
): List<String> {
    val wav = audioFile.absolutePath
    if (template.isEmpty()) return listOf(wav)
    return template.mapNotNull { token ->
        when (token) {
            "\${wav}" -> "--wav=$wav"
            "\${language}" -> params?.languageName?.let { "--language=$it" }
            "\${book}" -> params?.bookTitle?.let { "--book=$it" }
            "\${book_slug}" -> params?.bookSlug?.let { "--book_slug=$it" }
            "\${chapter}" -> params?.chapterLabel?.let { "--chapter=$it" }
            "\${chapter_number}" -> params?.let { "--chapter_number=${it.chapterNumber}" }
            "\${marker_labels}" -> params?.verseLabels?.let { "--marker_labels=${it.joinToString(",")}" }
            "\${marker_total}" -> params?.verseTotal?.let { "--marker_total=$it" }
            "\${unit}" -> params?.chunkLabel?.let { "--unit=$it" }
            "\${unit_number}" -> params?.chunkNumber?.let { "--unit_number=$it" }
            "\${source_text}" -> params?.sourceText?.let { "--source_text=$it" }
            "\${chapter_audio}" -> params?.sourceChapterAudio?.let { "--chapter_audio=${it.absolutePath}" }
            "\${source_language}" -> params?.sourceLanguageName?.let { "--source_language=$it" }
            "\${license}" -> params?.license?.let { "--license=$it" }
            else -> token.takeIf { !it.startsWith("\${") } // pass through literal args; drop unknown tokens
        }
    }
}
