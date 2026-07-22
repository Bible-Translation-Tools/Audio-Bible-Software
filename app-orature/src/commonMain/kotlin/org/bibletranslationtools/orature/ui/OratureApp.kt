package org.bibletranslationtools.orature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import org.bibletranslationtools.orature.crash.OratureCrashReporter
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.applicationCloseBlocked
import org.bibletranslationtools.orature.ui.navigation.OratureNavigation
import org.bibletranslationtools.orature.ui.screens.OratureCrashScreen
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.shared.preferences.AppSettings
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.shared.preferences.ThemeMode
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform.getKoin
import java.util.Locale

// Orature's own light palette (from the real JVM app's -wa- light-theme CSS vars),
// deliberately distinct from the recorder's branding.
object OratureColors {
    val Primary = Color(0xFF015AD9)
    val PrimaryDark = Color(0xFF0040A0)
    val PrimaryDarkest = Color(0xFF001847)
    val PrimaryLight = Color(0xFFEEF0FF)
    val Background = Color(0xFFF4F4F4)
    val Foreground = Color(0xFFFFFFFF)
    val SurfaceSecondary = Color(0xFFF2F2F2)
    val SurfaceTertiary = Color(0xFFE6E6E6) // also the standard border color (-wa-surface-border)
    val CardPlaceholderBackground = Color(0xFFF2F3F5) // -wa-card-image-placeholder-background
    val CardGraphic = Color(0xFFE5E8EB) // creation-card placeholder bars
    val TableHeaderBackground = Color(0xFFDCE0E5) // -wa-table-header-background
    val OnPrimary = Color(0xFFFFFFFF)
    val RegularText = Color(0xFF001533)
    val RegularText80 = Color(0xFF33445C)
    val NoteText = Color(0xB3001533) // rgba(0,21,51,0.7)
    val Disabled = Color(0x661A1A1A) // rgba(26,26,26,0.4)
    val BorderLight = Color(0x1A1A1A1A) // -wa-border-light: rgba(26,26,26,0.1)
    val BtnIconBorderColor = Color(0x33001533) // -wa-btn-icon-border-color: -wa-black-20 = rgba(0,21,51,0.2)
    val StatusComplete = Color(0xFF82A93F)
    val Accent = Color(0xFFFFB100)

    // Orature DARK -wa- palette.
    val DarkPrimary = Color(0xFF88A9FF)
    val DarkBackground = Color(0xFF1C2031)
    val DarkForeground = Color(0xFF373949)
    val DarkSurfaceSecondary = Color(0xFF1F1F1F)
    val DarkOnPrimary = Color(0xFF001533)
    val DarkRegularText = Color(0xFFF4F4F4)
}

private val OratureLightColors = lightColorScheme(
    primary = OratureColors.Primary,
    onPrimary = OratureColors.OnPrimary,
    secondary = OratureColors.Primary,
    onSecondary = OratureColors.OnPrimary,
    background = OratureColors.Background,
    onBackground = OratureColors.RegularText,
    surface = OratureColors.Foreground,
    onSurface = OratureColors.RegularText,
    surfaceVariant = OratureColors.SurfaceSecondary,
    onSurfaceVariant = OratureColors.RegularText
)

private val OratureDarkColors = darkColorScheme(
    primary = OratureColors.DarkPrimary,
    onPrimary = OratureColors.DarkOnPrimary,
    secondary = OratureColors.DarkPrimary,
    onSecondary = OratureColors.DarkOnPrimary,
    background = OratureColors.DarkBackground,
    onBackground = OratureColors.DarkRegularText,
    surface = OratureColors.DarkForeground,
    onSurface = OratureColors.DarkRegularText,
    surfaceVariant = OratureColors.DarkSurfaceSecondary,
    onSurfaceVariant = OratureColors.DarkRegularText
)

/**
 * Orature's theme. Observes the persisted [ThemeMode] from the shared [IAppPreferences]:
 * SYSTEM follows the OS dark-mode setting, LIGHT/DARK force a palette. Because it reads a
 * StateFlow, changing the theme in the settings drawer recomposes the whole app live.
 */
@Composable
fun OratureTheme(content: @Composable () -> Unit) {
    val appPreferences = remember { getKoin().get<IAppPreferences>() }
    val settings by appPreferences.appSettings.collectAsState(initial = AppSettings())

    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) OratureDarkColors else OratureLightColors,
        content = content
    )
}

/**
 * Root composable for the Orature app: its own theme + the persistent [OratureRootShell]
 * (nav rail + Settings/Info drawers) wrapping the navigation host, over the shared
 * backend/engine. The rail stays present on every screen (JVM: RootView's AppBar).
 */
@Composable
fun OratureApp(startWithSplash: Boolean = true) {
    val navController = rememberNavController()
    val appPreferences = remember { getKoin().get<IAppPreferences>() }
    val navigationLock = remember { getKoin().get<OratureNavigationLock>() }
    val snackbarHostState = remember { SnackbarHostState() }

    // JVM: `showNotification(messages["applicationCloseBlocked"], snackBarRoot)` — the desktop
    // window's onCloseRequest (outside Compose entirely) can't show UI itself, so it just notifies
    // this lock; here we turn that into the actual snackbar.
    LaunchedEffect(navigationLock) {
        navigationLock.closeBlockedEvents.collect {
            snackbarHostState.showSnackbar(getString(Res.string.applicationCloseBlocked))
        }
    }

    // Restore persisted preferences once at startup: apply the chosen UI locale and
    // re-select the remembered audio devices (mirrors the recorder's App.kt).
    LaunchedEffect(Unit) {
        val s = appPreferences.appSettings.first()
        s.appLanguageTag?.takeIf { it.isNotBlank() }?.let {
            runCatching { Locale.setDefault(Locale.forLanguageTag(it)) }
        }
        runCatching {
            val selector = getKoin().get<AudioDeviceSelector>()
            val spec = AudioSpec()
            s.outputDeviceId?.let { id ->
                selector.getOutputDevices(spec).firstOrNull { it.id == id }
                    ?.let(selector::selectOutputDevice)
            }
            s.inputDeviceId?.let { id ->
                selector.getInputDevices(spec).firstOrNull { it.id == id }
                    ?.let(selector::selectInputDevice)
            }
        }
    }

    OratureTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                OratureRootShell(navController) {
                    OratureNavigation(navController, startWithSplash = startWithSplash)
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Global crash overlay (JVM: OtterExceptionHandler -> ExceptionDialog). Sits above
            // everything — including safe-drawing padding — when an uncaught exception is captured.
            val crash by OratureCrashReporter.crash.collectAsState()
            crash?.let { OratureCrashScreen(it) }
        }
    }
}
