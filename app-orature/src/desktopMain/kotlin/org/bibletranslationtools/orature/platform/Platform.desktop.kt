package org.bibletranslationtools.orature.platform

import java.awt.Desktop
import java.net.URI

actual fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

// The packaged jar's Implementation-Version (set by the build manifest); a dev fallback otherwise.
actual fun appVersion(): String =
    object {}.javaClass.`package`?.implementationVersion ?: "1.0.0-dev"
