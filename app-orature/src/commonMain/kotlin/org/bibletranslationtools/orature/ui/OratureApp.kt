package org.bibletranslationtools.orature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import org.bibletranslationtools.orature.ui.navigation.OratureNavigation

// Orature's OWN theme — deliberately distinct from the recorder's branding. Placeholder
// palette for the shell; refined as Orature screens are built (Part B).
@Composable
fun OratureTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

/**
 * Root composable for the Orature app: its own theme + navigation host over the shared
 * backend/engine. Real screens arrive through the nav graph in Part B.
 */
@Composable
fun OratureApp() {
    val navController = rememberNavController()
    OratureTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                OratureNavigation(navController)
            }
        }
    }
}
