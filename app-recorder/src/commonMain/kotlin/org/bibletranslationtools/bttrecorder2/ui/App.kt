package org.bibletranslationtools.bttrecorder2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import org.bibletranslationtools.shared.preferences.AppSettings
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.shared.preferences.ThemeMode
import org.bibletranslationtools.bttrecorder2.ui.navigation.Navigation
import org.bibletranslationtools.bttrecorder2.ui.theme.TranslationRecorderTheme
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.koin.mp.KoinPlatform.getKoin
import java.util.Locale

@Composable
fun App() {
    val navController = rememberNavController()
    val appPreferences = remember { getKoin().get<IAppPreferences>() }
    val settings by appPreferences.appSettings.collectAsState(initial = AppSettings())

    // Restore persisted preferences once at startup: apply the chosen app locale
    // and re-select the remembered audio devices (overriding any default-first
    // selection done during platform startup).
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

    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (dark) {
        TranslationRecorderTheme.DarkTranslationRecorderColorScheme
    } else {
        TranslationRecorderTheme.LightTranslationRecorderColorScheme
    }

    MaterialTheme(colorScheme = colorScheme) {
        // Paint the app background under the system bars so the inset area
        // matches the app theme, then push the actual UI inside the safe
        // drawing area (status bar + 3-button nav bar + display cutouts).
        // On platforms without system bars (Desktop, iOS web) this is a no-op.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
        ) {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Navigation(navController)
            }
        }
    }
}
