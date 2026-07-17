package org.bibletranslationtools.orature

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.bibletranslationtools.orature.di.oratureDirectoryProviderModule
import org.bibletranslationtools.orature.di.oratureViewModelModule
import org.bibletranslationtools.orature.ui.OratureApp
import org.bibletranslationtools.orature.ui.OratureNavigationLock
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.device.newaudio.AudioSystemConfig
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.bibletranslationtools.shared.di.koin.sharedDesktopModules
import org.koin.core.context.startKoin

fun main() {
    System.setProperty("apple.awt.application.name", "Orature")

    // Route logging to a file in the app's logs dir BEFORE anything logs (slf4j-simple reads its
    // config on first use), so Info → View Logs opens a folder with real logs. Uses the real
    // DirectoryProvider path logic rather than duplicating it.
    runCatching {
        val logsDir = DesktopDirectoryProvider(
            appName = "Orature2",
            pathSeparator = System.getProperty("file.separator"),
            userHome = System.getProperty("user.home"),
            windowsAppData = System.getenv("APPDATA"),
            osName = System.getProperty("os.name").uppercase()
        ).logsDirectory.apply { mkdirs() }
        System.setProperty("org.slf4j.simpleLogger.logFile", java.io.File(logsDir, "orature.log").absolutePath)
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS")
    }

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

    val navigationLock = koin.get<OratureNavigationLock>()

    application {
        // JVM Orature opens maximized (OtterApp.start: stage.isMaximized = true), not at a fixed
        // pixel size.
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
        Window(
            // JVM: `OtterApp`'s `setOnCloseRequest` — veto the close (and show a snackbar) while
            // an external editor plugin is open, instead of exiting normally.
            onCloseRequest = {
                if (navigationLock.locked.value) {
                    navigationLock.notifyCloseBlocked()
                } else {
                    exitApplication()
                }
            },
            title = "Orature",
            state = windowState
        ) {
            OratureApp()
        }
    }
}
