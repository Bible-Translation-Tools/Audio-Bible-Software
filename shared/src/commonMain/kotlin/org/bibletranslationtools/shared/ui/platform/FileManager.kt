package org.bibletranslationtools.shared.ui.platform

import io.github.vinceglb.filekit.dialogs.FileKitType
import java.io.File

/**
 * The file-picker filter for importing [extensions]. Desktop filters by extension. Android allows
 * all files: SAF matches by MIME type, and FileKit drops extensions with no registered MIME
 * (.orature, .tstudio), so an extension filter grays those files out. Importers validate content
 * regardless.
 */
expect fun importPickerType(extensions: List<String> = listOf("orature", "zip", "tstudio")): FileKitType

/** True when the OS file manager can be opened from the app (desktop only). */
expect fun canOpenInFileManager(): Boolean

/**
 * Reveal [file] in the OS file manager (JVM: java.awt.Desktop.open on the file's directory). Opens
 * the containing folder when [file] is a file. No-op where unsupported (Android).
 */
expect fun openInFileManager(file: File)
