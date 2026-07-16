package org.bibletranslationtools.orature.platform

import io.github.vinceglb.filekit.dialogs.FileKitType
import java.awt.Desktop
import java.io.File
import java.net.URI

actual fun importPickerType(): FileKitType =
    FileKitType.File(extensions = listOf("orature", "zip", "tstudio"))

actual fun canOpenInFileManager(): Boolean =
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)

actual fun openInFileManager(file: File) {
    runCatching {
        val target = if (file.isDirectory) file else file.parentFile ?: return
        Desktop.getDesktop().open(target)
    }
}

actual fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
