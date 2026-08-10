package org.bibletranslationtools.orature.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

// Named rather than derived from a class: these are top-level `actual` functions, so there is no
// instance for shared.logging.logFailure to take a logger name from.
private val logger = LoggerFactory.getLogger("org.bibletranslationtools.orature.plugins.PluginLauncher")

actual fun canLaunchPlugins(): Boolean = true

/**
 * On macOS a `.app` is a directory; resolve it to the inner binary Contents/MacOS/<exe> so it can be
 * launched as a process (JVM: AddPluginViewModel.completePluginPath). Elsewhere, identity.
 */
actual fun resolvePluginExecutable(path: String): String {
    val trimmed = path.trimEnd('/')
    val file = File(trimmed)
    if (file.isDirectory && trimmed.endsWith(".app")) {
        val macBin = File(file, "Contents/MacOS")
        if (macBin.exists()) {
            // May contain multiple files; take the first (JVM: listFiles().first()). The user can
            // edit the path afterward if it's wrong.
            macBin.listFiles()?.firstOrNull()?.let { return it.absolutePath }
        }
    }
    return path
}

actual suspend fun runPluginProcess(command: List<String>): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        while (process.inputStream.read() >= 0) { /* drain so the child never blocks on a full pipe */ }
        process.waitFor() == 0
    }.getOrElse {
        logger.error("Failed: launching the plugin process $command", it)
        false
    }
}
