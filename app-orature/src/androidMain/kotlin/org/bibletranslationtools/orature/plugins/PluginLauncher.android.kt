package org.bibletranslationtools.orature.plugins

// External editors launch a desktop process; not available on Android.
actual fun canLaunchPlugins(): Boolean = false

actual fun resolvePluginExecutable(path: String): String = path

actual suspend fun runPluginProcess(command: List<String>): Boolean = false
