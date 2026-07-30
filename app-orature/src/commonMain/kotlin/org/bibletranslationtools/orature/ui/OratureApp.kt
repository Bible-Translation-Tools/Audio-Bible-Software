package org.bibletranslationtools.orature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import org.bibletranslationtools.orature.crash.OratureCrashReporter
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.applicationCloseBlocked
import org.bibletranslationtools.orature.resources.openBook
import org.bibletranslationtools.orature.ui.navigation.OratureNarrationRoute
import org.bibletranslationtools.orature.ui.navigation.OratureNavigation
import org.bibletranslationtools.orature.ui.navigation.OratureTranslationRoute
import org.bibletranslationtools.orature.ui.screens.OratureCrashScreen
import org.bibletranslationtools.orature.ui.viewmodels.OratureImportEvents
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.device.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.bibletranslationtools.shared.preferences.AppSettings
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.shared.preferences.ThemeMode
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform.getKoin
import java.util.Locale

// Orature's semantic palette, mapped from the JVM app's -wa- light-theme.css / dark-theme.css vars.
// The whole UI reads these directly (not just via MaterialTheme.colorScheme), so each color resolves
// light/dark from [darkTheme] — set by OratureTheme, which re-keys the tree on theme change so these
// getters are re-read. This makes dark mode apply everywhere, not only the few color-scheme roles.
object OratureColors {
    /** Set by OratureTheme before (re)composing the app; see the key(dark) there. */
    var darkTheme: Boolean = false

    private fun pick(light: Long, dark: Long) = Color(if (darkTheme) dark else light)

    val Primary get() = pick(0xFF015AD9, 0xFF88A9FF)
    val PrimaryDark get() = pick(0xFF0040A0, 0xFFB2C5FF)
    val PrimaryDarkest get() = pick(0xFF001847, 0xFFDAE2FF)
    val PrimaryLight get() = pick(0xFFEEF0FF, 0xFF00102E)
    val Background get() = pick(0xFFF4F4F4, 0xFF1F1F1F)            // -wa-background
    val Foreground get() = pick(0xFFFFFFFF, 0xFF343434)           // -wa-foreground / surface-primary
    val SurfaceSecondary get() = pick(0xFFF2F2F2, 0xFF1F1F1F)     // -wa-surface-secondary
    val SurfaceTertiary get() = pick(0xFFE6E6E6, 0xFF4D4D4D)      // surfaces + standard borders/dividers
    val CardPlaceholderBackground get() = pick(0xFFF2F3F5, 0xFF3A3A3A)
    val CardGraphic get() = pick(0xFFE5E8EB, 0xFF4D4D4D)          // creation-card placeholder bars
    val TableHeaderBackground get() = pick(0xFFDCE0E5, 0xFF4D4D4D) // -wa-table-header-background
    val OnPrimary get() = pick(0xFFFFFFFF, 0xFF001533)           // text on the primary fill
    val RegularText get() = pick(0xFF001533, 0xFFE6E6E6)         // -wa-regular-text
    val RegularText80 get() = pick(0xFF33445C, 0xFFCCCCCC)
    val NoteText get() = pick(0xB3001533, 0xFFC0BFBF)            // -wa-note-text
    val Disabled get() = pick(0x661A1A1A, 0x66FFFFFF)            // -wa-disabled-text
    val BorderLight get() = pick(0x1A1A1A1A, 0x1AFFFFFF)         // -wa-border-light
    val BtnIconBorderColor get() = pick(0x33001533, 0x33FFFFFF)  // -wa-btn-icon-border-color
    val StatusComplete get() = pick(0xFF82A93F, 0xFF2EC144)      // -wa-status-complete
    val Accent get() = pick(0xFFFFB100, 0xFFF39422)             // -wa-accent

    // Waveform rendering (JVM common/data/ColorTheme.kt: WAV_COLOR_* / WAV_BACKGROUND_COLOR_*).
    val WaveformLine get() = pick(0xFF66768B, 0xFF808080)        // WAV_COLOR_LIGHT / WAV_COLOR_DARK
    val WaveformBackground get() = pick(0xFFFFFFFF, 0xFF343434)  // WAV_BACKGROUND_COLOR_LIGHT / _DARK

    // Explicit dark values for the dark color scheme below (JVM dark-theme.css).
    val DarkPrimary = Color(0xFF88A9FF)
    val DarkBackground = Color(0xFF1F1F1F)   // -wa-background (was #1C2031)
    val DarkForeground = Color(0xFF343434)   // -wa-foreground (was #373949)
    val DarkSurfaceSecondary = Color(0xFF1F1F1F)
    val DarkOnPrimary = Color(0xFF001533)
    val DarkRegularText = Color(0xFFE6E6E6)  // -wa-regular-text (was #F4F4F4)
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
    // Flip the whole UI to RTL for right-to-left UI languages (JVM: setAppOrientation ->
    // NodeOrientation.RIGHT_TO_LEFT). Driven by the selected UI language so changing it in the
    // settings drawer re-lays-out the app live.
    val layoutDirection = if (isRtlLanguage(settings.appLanguageTag)) LayoutDirection.Rtl else LayoutDirection.Ltr
    // The UI reads OratureColors.* directly; point them at the light/dark set for this theme.
    OratureColors.darkTheme = dark
    MaterialTheme(colorScheme = if (dark) OratureDarkColors else OratureLightColors) {
        // Re-key the whole tree on language AND theme: Compose caches stringResource() by Locale.current
        // (so a Locale.setDefault language change wouldn't otherwise re-translate live), and our
        // OratureColors.* getters read the plain `darkTheme` flag (not Compose state), so a theme flip
        // needs a forced recompose to re-read them. Keying on both rebuilds against the current values.
        key(settings.appLanguageTag, dark) {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection, content = content)
        }
    }
}

// The UI languages Orature ships that are written right-to-left (today only Arabic; the rest guard
// against future additions). Compared on the language subtag so region variants still match.
private val RTL_LANGUAGES = setOf("ar", "he", "iw", "fa", "ur", "ps", "sd", "yi", "dv")

private fun isRtlLanguage(tag: String?): Boolean {
    val lang = if (tag.isNullOrBlank()) Locale.getDefault().language else Locale.forLanguageTag(tag).language
    return lang.lowercase() in RTL_LANGUAGES
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

    // Import result messages (JVM: SnackbarHandler.showNotification at the app root) — success/failure
    // are snackbars here, not dialogs. A successful project import offers an "Open Book" action that
    // navigates to the imported book (JVM: the notification's Open Book action).
    val importEvents = remember { getKoin().get<OratureImportEvents>() }
    LaunchedEffect(importEvents) {
        importEvents.notifications.collect { n ->
            val actionLabel = if (n.workbookDescriptorId != null) getString(Res.string.openBook) else null
            val result = snackbarHostState.showSnackbar(
                message = n.message,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed && n.workbookDescriptorId != null) {
                val route = if (n.mode == ProjectMode.TRANSLATION) {
                    OratureTranslationRoute(n.workbookDescriptorId)
                } else {
                    OratureNarrationRoute(n.workbookDescriptorId)
                }
                navController.navigate(route)
            }
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
