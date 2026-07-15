package org.bibletranslationtools.orature.platform

import java.io.File

/** True when the OS file manager can be opened from the app (desktop only). */
expect fun canOpenInFileManager(): Boolean

/**
 * Reveal [file] in the OS file manager (JVM: java.awt.Desktop.open on the file's directory). Opens
 * the containing folder when [file] is a file. No-op where unsupported (Android).
 */
expect fun openInFileManager(file: File)

/** Open [url] in the default browser (desktop). No-op where unsupported (Android). */
expect fun openUrl(url: String)
