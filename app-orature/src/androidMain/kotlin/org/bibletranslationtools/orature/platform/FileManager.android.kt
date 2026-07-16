package org.bibletranslationtools.orature.platform

import io.github.vinceglb.filekit.dialogs.FileKitType
import org.bibletranslationtools.orature.BuildConfig
import java.io.File

// SAF matches by MIME type; .orature/.tstudio have none, so an extension filter would hide them.
actual fun importPickerType(): FileKitType = FileKitType.File()

// Android has no user-facing "open folder in file manager" for app-private export dirs; no-op.
actual fun canOpenInFileManager(): Boolean = false

actual fun openInFileManager(file: File) { /* no-op */ }

actual fun openUrl(url: String) { /* no-op */ }

actual fun appVersion(): String = BuildConfig.VERSION_NAME
