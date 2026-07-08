package org.bibletranslationtools.recorder2

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.Taskbar
import java.awt.Toolkit
import org.bibletranslationtools.bttrecorder2.di.koin.commonAudioModule
import org.bibletranslationtools.bttrecorder2.di.koin.commonModules
import org.bibletranslationtools.bttrecorder2.ui.App
import org.bibletranslationtools.bttrecorder2.ui.demo.AudioDashboard

import org.bibletranslationtools.di.koin.appModules
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.device.newaudio.AudioSystemConfig
import org.bibletranslationtools.recorder2.di.jvmAudioModule
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
        modules(*appModules.toTypedArray(), commonAudioModule, jvmAudioModule)
    }.koin

    // Initialize audio routing at startup so recorder/player factories are not left on dummy hardware.
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

//fun main() = application {
//    val koinApp = startKoin {
//        modules(*appModules.toTypedArray(), commonAudioModule, jvmAudioModule)
//    }.koin
//
//    val config = koinApp.get<AudioSystemConfig>()
//    val selector = koinApp.get<AudioDeviceSelector>()
//    val playerFactory = koinApp.get<AudioPlayerConnectionFactory>()
//    val directoryProvider = koinApp.get<IDirectoryProvider>()
//
//    // Start the observer that hot-swaps hardware
//    config.start()
//
//    // Load initial defaults if needed
//    val defaultSpec = AudioSpec()
//    selector.getOutputDevices(defaultSpec).firstOrNull()?.let {
//        selector.selectOutputDevice(it)
//    }
//
//    Window(onCloseRequest = ::exitApplication, title = "Otter Audio Test") {
//        MaterialTheme {
//            AudioDashboard(playerFactory, selector, directoryProvider)
//        }
//    }
//}
