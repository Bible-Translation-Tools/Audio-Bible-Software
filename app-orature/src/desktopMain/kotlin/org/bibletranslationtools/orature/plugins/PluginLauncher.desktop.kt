package org.bibletranslationtools.orature.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        System.err.println("Failed to launch plugin process $command: $it")
        false
    }
}
