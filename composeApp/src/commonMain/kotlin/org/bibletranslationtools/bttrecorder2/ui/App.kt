package org.bibletranslationtools.bttrecorder2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import org.bibletranslationtools.bttrecorder2.ui.navigation.Navigation
import org.bibletranslationtools.bttrecorder2.ui.theme.TranslationRecorderTheme
import org.bibletranslationtools.otter.common.di.DependencyProvider

@Composable
fun App() {
    val navController = rememberNavController()
    val colorScheme = if (isSystemInDarkTheme()) {
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