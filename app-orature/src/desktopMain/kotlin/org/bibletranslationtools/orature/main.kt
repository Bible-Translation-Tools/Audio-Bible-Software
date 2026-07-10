package org.bibletranslationtools.orature

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.bibletranslationtools.orature.di.oratureDirectoryProviderModule
import org.bibletranslationtools.orature.di.oratureViewModelModule
import org.bibletranslationtools.orature.ui.OratureApp
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.device.newaudio.AudioSystemConfig
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.bibletranslationtools.shared.di.koin.sharedDesktopModules
import org.koin.core.context.startKoin

fun main() {
    System.setProperty("apple.awt.application.name", "Orature")

    // Compose the shared backend/engine Koin graph + Orature's own directory provider
    // and ViewModels.
    val koin = startKoin {
        modules(
            sharedCommonModules + sharedDesktopModules + oratureDirectoryProviderModule +
                oratureViewModelModule
        )
    }.koin

    // Start audio routing so the player/recorder factories bind to real hardware instead of
    // the default null-line sink (otherwise loading audio throws "No SourceDataLine available").
    // Mirrors the recorder's main(): observe device changes, then select a default in/out device.
    // App.kt's LaunchedEffect later re-applies the user's remembered devices over these defaults.
    val config = koin.get<AudioSystemConfig>()
    val selector = koin.get<AudioDeviceSelector>()
    val defaultSpec = AudioSpec()
    config.start()
    selector.getOutputDevices(defaultSpec).firstOrNull()?.let(selector::selectOutputDevice)
    selector.getInputDevices(defaultSpec).firstOrNull()?.let(selector::selectInputDevice)

    application {
        Window(onCloseRequest = ::exitApplication, title = "Orature") {
            OratureApp()
        }
    }
}
