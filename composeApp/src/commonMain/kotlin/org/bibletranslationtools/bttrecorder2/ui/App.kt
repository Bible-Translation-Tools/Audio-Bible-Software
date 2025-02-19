package org.bibletranslationtools.bttrecorder2.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import org.bibletranslationtools.bttrecorder2.ui.navigation.Navigation
import org.bibletranslationtools.bttrecorder2.ui.theme.TranslationRecorderTheme
import org.bibletranslationtools.otter.common.di.DependencyProvider

@Composable
fun App(dependencyProvider: DependencyProvider) {
    val navController = rememberNavController()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) {
            TranslationRecorderTheme.DarkTranslationRecorderColorScheme
        } else {
            TranslationRecorderTheme.LightTranslationRecorderColorScheme
        }
    ) {
        Navigation(navController, dependencyProvider)
    }
}