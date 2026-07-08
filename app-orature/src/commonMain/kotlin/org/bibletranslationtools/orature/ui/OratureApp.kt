package org.bibletranslationtools.orature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.rememberNavController
import org.bibletranslationtools.orature.ui.navigation.OratureNavigation

// Orature's own light palette (from the real JVM app's -wa- light-theme CSS vars),
// deliberately distinct from the recorder's branding.
object OratureColors {
    val Primary = Color(0xFF015AD9)
    val Background = Color(0xFFF4F4F4)
    val Foreground = Color(0xFFFFFFFF)
    val SurfaceSecondary = Color(0xFFF2F2F2)
    val OnPrimary = Color(0xFFFFFFFF)
    val RegularText = Color(0xFF001533)
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

@Composable
fun OratureTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OratureLightColors, content = content)
}

/**
 * Root composable for the Orature app: its own theme + navigation host over the shared
 * backend/engine. The persistent nav rail lives inside the home screen's own Scaffold for
 * now (Phase 1) — a fuller RootView shell that hosts the rail above the nav graph arrives
 * in a later phase.
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
