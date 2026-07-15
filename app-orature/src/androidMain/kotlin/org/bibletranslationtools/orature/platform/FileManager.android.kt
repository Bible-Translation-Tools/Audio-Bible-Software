package org.bibletranslationtools.orature.platform

import java.io.File

// Android has no user-facing "open folder in file manager" for app-private export dirs; no-op.
actual fun canOpenInFileManager(): Boolean = false

actual fun openInFileManager(file: File) { /* no-op */ }

actual fun openUrl(url: String) { /* no-op */ }
