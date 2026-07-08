package org.bibletranslationtools.orature

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.bibletranslationtools.orature.di.oratureDirectoryProviderModule
import org.bibletranslationtools.orature.ui.OratureApp
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.bibletranslationtools.shared.di.koin.sharedDesktopModules
import org.koin.core.context.startKoin

fun main() {
    System.setProperty("apple.awt.application.name", "Orature")

    // Compose the shared backend/engine Koin graph + Orature's own directory provider.
    // (Orature's ViewModels get their own module in Part B.)
    startKoin {
        modules(sharedCommonModules + sharedDesktopModules + oratureDirectoryProviderModule)
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "Orature") {
            OratureApp()
        }
    }
}
