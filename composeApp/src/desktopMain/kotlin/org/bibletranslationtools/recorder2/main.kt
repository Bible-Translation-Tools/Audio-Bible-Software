package org.bibletranslationtools.recorder2

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.navigation.compose.rememberNavController
import org.bibletranslationtools.bttrecorder2.ui.App
import org.bibletranslationtools.bttrecorder2.ui.navigation.Navigation
import org.bibletranslationtools.bttrecorder2.ui.screens.MainMenuScreen

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "BTT-Recorder2",
    ) {
        App()
    }
}