package org.bibletranslationtools.shared.ui.platform

import io.github.vinceglb.filekit.dialogs.FileKitType
import java.io.File

actual fun importPickerType(extensions: List<String>): FileKitType = FileKitType.File()

// Android has no user-facing "open folder in file manager" for app-private export dirs; no-op.
actual fun canOpenInFileManager(): Boolean = false

actual fun openInFileManager(file: File) { /* no-op */ }
