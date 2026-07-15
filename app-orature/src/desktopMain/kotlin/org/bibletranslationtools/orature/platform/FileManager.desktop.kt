package org.bibletranslationtools.orature.platform

import java.awt.Desktop
import java.io.File

actual fun canOpenInFileManager(): Boolean =
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)

actual fun openInFileManager(file: File) {
    runCatching {
        val target = if (file.isDirectory) file else file.parentFile ?: return
        Desktop.getDesktop().open(target)
    }
}
