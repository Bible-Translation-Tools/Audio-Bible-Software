package org.bibletranslationtools.recorder2

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.Taskbar
import java.awt.Toolkit
import org.bibletranslationtools.bttrecorder2.di.koin.recorderViewModelModule
import org.bibletranslationtools.bttrecorder2.ui.App
import org.bibletranslationtools.di.koin.directoryProviderModule
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.bibletranslationtools.shared.di.koin.sharedDesktopModules
import org.bibletranslationtools.otter.common.device.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.bibletranslationtools.otter.common.device.AudioSystemConfig
import org.koin.core.context.startKoin

fun main() {
    // Must be set before AWT initialises — controls the macOS menu-bar app name and Dock tooltip.
    System.setProperty("apple.awt.application.name", "BTT-Recorder")

    // Set the macOS Dock icon before the application loop starts.
    // Window(icon=...) only affects the window decoration; Taskbar controls the Dock.
    runCatching {
        if (Taskbar.isTaskbarSupported()) {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                val iconUrl = Thread.currentThread().contextClassLoader
                    .getResource("icons/ic_launcher.png")
                taskbar.iconImage = Toolkit.getDefaultToolkit().getImage(iconUrl)
            }
        }
    }
    application {
        val koin = startKoin {
            modules(
                sharedCommonModules + sharedDesktopModules +
                    directoryProviderModule + recorderViewModelModule
            )
        }.koin

        // Initialize audio routing at startup so recorder/player factories are not left on
        // dummy hardware.
        val config = koin.get<AudioSystemConfig>()
        val selector = koin.get<AudioDeviceSelector>()
        val defaultSpec = AudioSpec()
        config.start()
        selector.getOutputDevices(defaultSpec).firstOrNull()?.let { selector.selectOutputDevice(it) }
        selector.getInputDevices(defaultSpec).firstOrNull()?.let { selector.selectInputDevice(it) }

        Window(
            onCloseRequest = ::exitApplication,
            title = "BTT-Recorder2",
            icon = painterResource("icons/ic_launcher.png")
        ) {
            App()
        }
    }
}
