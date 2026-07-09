package org.bibletranslationtools.orature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import org.bibletranslationtools.orature.ui.components.OratureInfoDrawer
import org.bibletranslationtools.orature.ui.components.OratureNavDestination
import org.bibletranslationtools.orature.ui.components.OratureNavRail
import org.bibletranslationtools.orature.ui.components.OratureSettingsDrawer
import org.bibletranslationtools.orature.ui.navigation.OratureHomeRoute
import org.bibletranslationtools.orature.ui.navigation.OratureSplashRoute

/** Which left drawer (if any) is currently open over the content. */
private enum class OpenDrawer { NONE, SETTINGS, INFO }

/**
 * The persistent app shell (JVM: `RootView` = `borderpane { left<AppBar>; center<AppContent> }`).
 * The nav rail lives here, OUTSIDE the nav graph, so it stays present on every screen — home,
 * the project mode pages, everywhere — while [content] (the NavHost) swaps beneath it. The
 * Settings/Info drawers are toggled by the rail and overlay whatever content is showing, so
 * they too are hoisted here and work from any screen.
 *
 * The rail is hidden only on the splash route (a full-bleed loading screen), matching the JVM
 * app where RootView appears after the splash.
 */
@Composable
fun OratureRootShell(
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    var openDrawer by remember { mutableStateOf(OpenDrawer.NONE) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val isSplash = destination?.hasRoute(OratureSplashRoute::class) == true
    val isHome = destination?.hasRoute(OratureHomeRoute::class) == true

    val selected: OratureNavDestination? = when {
        openDrawer == OpenDrawer.SETTINGS -> OratureNavDestination.SETTINGS
        openDrawer == OpenDrawer.INFO -> OratureNavDestination.INFO
        isHome -> OratureNavDestination.HOME
        else -> null
    }

    Row(modifier = Modifier.fillMaxSize()) {
        if (!isSplash) {
            OratureNavRail(
                selected = selected,
                onHomeClick = {
                    openDrawer = OpenDrawer.NONE
                    if (!isHome) {
                        navController.navigate(OratureHomeRoute) {
                            popUpTo(OratureHomeRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onSettingsClick = {
                    openDrawer = if (openDrawer == OpenDrawer.SETTINGS) OpenDrawer.NONE else OpenDrawer.SETTINGS
                },
                onInfoClick = {
                    openDrawer = if (openDrawer == OpenDrawer.INFO) OpenDrawer.NONE else OpenDrawer.INFO
                }
            )
        }

        // Content area (right of the rail, or full-bleed on splash); drawers overlay it.
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            content()

            if (!isSplash && openDrawer != OpenDrawer.NONE) {
                // Dimming scrim: click outside closes the drawer (JVM: HiddenSidesPane + dim overlay).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { openDrawer = OpenDrawer.NONE }
                )
                when (openDrawer) {
                    OpenDrawer.SETTINGS -> OratureSettingsDrawer(
                        onClose = { openDrawer = OpenDrawer.NONE },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    OpenDrawer.INFO -> OratureInfoDrawer(
                        onClose = { openDrawer = OpenDrawer.NONE },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    OpenDrawer.NONE -> Unit
                }
            }
        }
    }
}
