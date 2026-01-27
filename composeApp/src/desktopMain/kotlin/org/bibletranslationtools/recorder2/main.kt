package org.bibletranslationtools.recorder2

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.bibletranslationtools.bttrecorder2.ui.App

import org.bibletranslationtools.di.koin.appModules
import org.koin.core.context.startKoin

fun main() = application {

    startKoin {
        modules(appModules)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "BTT-Recorder2",
    ) {
        App()
    }
}