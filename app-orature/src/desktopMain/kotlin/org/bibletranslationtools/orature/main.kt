package org.bibletranslationtools.orature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Taskbar
import java.awt.Toolkit
import kotlinx.coroutines.awaitCancellation
import org.bibletranslationtools.orature.di.oratureDirectoryProviderModule
import org.bibletranslationtools.orature.di.oratureViewModelModule
import org.bibletranslationtools.orature.ui.OratureApp
import org.bibletranslationtools.orature.ui.OratureNavigationLock
import org.bibletranslationtools.orature.ui.OratureTheme
import org.bibletranslationtools.orature.ui.screens.OratureSplashScreen
import org.bibletranslationtools.orature.ui.viewmodels.OratureSplashViewModel
import org.bibletranslationtools.otter.common.device.AudioConfig
import org.bibletranslationtools.shared.logging.DesktopFileLogging
import org.bibletranslationtools.otter.common.device.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.bibletranslationtools.otter.common.device.AudioSystemConfig
import org.bibletranslationtools.otter.common.device.JvmAudioLines
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.bibletranslationtools.shared.di.koin.sharedDesktopModules
import org.koin.core.context.startKoin

fun main() {
    System.setProperty("apple.awt.application.name", "Orature")

    // Route logging to a file in the app's logs dir BEFORE anything logs (slf4j-simple reads its
    // config on first use), so Info → View Logs opens a folder with real logs. Same path, same file
    // name, same env var as when this was inline here — it moved to :shared because the recorder app
    // had no equivalent, and an installed recorder build therefore produced no diagnostics at all.
    // ORATURE_LOG_LEVEL=debug turns on the shared.logging.logDebug diagnostics.
    DesktopFileLogging.install(
        appName = "Orature2",
        logFileName = "orature.log",
        logLevelEnvVar = "ORATURE_LOG_LEVEL"
    )

    // Safety net for a line nothing released: see JvmAudioLines for what this does and does not cover
    // (it is insurance for the way out, not a substitute for releasing the mic when a screen closes).
    JvmAudioLines.installShutdownHook()

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
    val defaultSpec = koin.get<AudioConfig>().spec
    config.start()
    selector.getOutputDevices(defaultSpec).firstOrNull()?.let(selector::selectOutputDevice)
    selector.getInputDevices(defaultSpec).firstOrNull()?.let(selector::selectInputDevice)

    // Install the global crash handler (JVM: Thread.setDefaultUncaughtExceptionHandler(OtterExceptionHandler)).
    // Reports upload to GitHub only if a github.properties is on the classpath; otherwise the crash
    // screen still shows (send disabled). Close = quit the process.
    run {
        val directoryProvider = koin.get<org.bibletranslationtools.otter.common.api.persistence.IAppDirectories>()
        org.bibletranslationtools.orature.crash.OratureCrashReporter.install(
            uploaders = listOfNotNull(
                org.bibletranslationtools.orature.crash.GithubCrashReportUploader.fromClasspath(),
                org.bibletranslationtools.orature.crash.SentryCrashReporter.fromClasspath()
            ),
            logProvider = {
                runCatching { java.io.File(directoryProvider.logsDirectory, "orature.log").readText() }.getOrNull()
            },
            closeApp = { kotlin.system.exitProcess(1) }
        )
    }

    // Set the macOS Dock / taskbar icon before the application loop starts. Window(icon=...) only
    // affects the window decoration; Taskbar controls the Dock (mirrors the recorder's main()).
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

    val navigationLock = koin.get<OratureNavigationLock>()

    application {
        // JVM Orature shows the splash in a SEPARATE undecorated window while the main (maximized)
        // window stays hidden, then reveals the main window once InitializeApp completes
        // (OtterApp: shouldShowPrimaryStage=false; SplashScreen.finish → primaryStage.show()).
        var initialized by remember { mutableStateOf(false) }

        if (!initialized) {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Orature",
                icon = painterResource("icons/ic_launcher.png"),
                undecorated = true,
                resizable = false,
                // Fixed to the splash art's size (orature_splash.png is 576×480), centered.
                state = rememberWindowState(
                    size = DpSize(576.dp, 480.dp),
                    position = WindowPosition(Alignment.Center)
                )
            ) {
                OratureSplashWindow(onFinished = { initialized = true })
            }
        } else {
            // JVM Orature opens maximized (OtterApp.start: stage.isMaximized = true).
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
                icon = painterResource("icons/ic_launcher.png"),
                state = windowState
            ) {
                // The splash already ran InitializeApp; the main window starts straight at Home.
                OratureApp(startWithSplash = false)
            }
        }
    }
}

/**
 * Splash window content: runs the backend init (DB migrate + seed) showing progress, then signals
 * [onFinished] so main.kt can swap in the maximized main window (JVM SplashScreen.finish()).
 */
@Composable
private fun OratureSplashWindow(onFinished: () -> Unit) {
    val vm = remember { OratureSplashViewModel() }
    LaunchedEffect(Unit) {
        val disposable = vm.initApp().subscribe({ onFinished() }, { onFinished() })
        try {
            awaitCancellation()
        } finally {
            disposable.dispose()
        }
    }
    OratureTheme {
        OratureSplashScreen(vm)
    }
}
