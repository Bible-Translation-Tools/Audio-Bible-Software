package org.bibletranslationtools.orature.platform

import io.github.vinceglb.filekit.dialogs.FileKitType
import java.io.File

/**
 * The file-picker filter for project import. Desktop filters by the Orature/RC/tstudio extensions
 * (nice UX); Android must allow all files, since SAF matches by MIME type and .orature/.tstudio have
 * no registered MIME (an extension filter greys them out). The importer validates content regardless.
 */
expect fun importPickerType(): FileKitType

/** True when the OS file manager can be opened from the app (desktop only). */
expect fun canOpenInFileManager(): Boolean

/**
 * Reveal [file] in the OS file manager (JVM: java.awt.Desktop.open on the file's directory). Opens
 * the containing folder when [file] is a file. No-op where unsupported (Android).
 */
expect fun openInFileManager(file: File)

/** Open [url] in the default browser (desktop). No-op where unsupported (Android). */
expect fun openUrl(url: String)

/** The app version string shown in the Info drawer (JVM: AppInfo.getVersion). */
expect fun appVersion(): String
