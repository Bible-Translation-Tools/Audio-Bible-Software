package org.bibletranslationtools.recorder2

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
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

fun main() = application {

    startKoin {
        modules(*appModules.toTypedArray(), commonAudioModule, jvmAudioModule)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "BTT-Recorder2",
    ) {
        App()
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