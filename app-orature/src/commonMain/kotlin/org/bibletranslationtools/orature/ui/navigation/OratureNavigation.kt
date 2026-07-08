package org.bibletranslationtools.orature.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.bibletranslationtools.orature.ui.screens.OratureHomeScreen
import org.bibletranslationtools.orature.ui.screens.OratureSplashScreen
import org.bibletranslationtools.orature.ui.viewmodels.OratureSplashViewModel

@Composable
fun OratureNavigation(navController: NavHostController) {
    val scope = rememberCoroutineScope()

    NavHost(navController = navController, startDestination = OratureSplashRoute) {
        composable<OratureSplashRoute> {
            val vm = viewModel { OratureSplashViewModel() }
            LaunchedEffect(Unit) {
                // Run backend init (DB migrate + seed); navigate home on completion.
                val disposable = vm.initApp().subscribe {
                    scope.launch {
                        navController.navigate(OratureHomeRoute) {
                            popUpTo(OratureSplashRoute) { inclusive = true }
                        }
                    }
                }
                try {
                    awaitCancellation()
                } finally {
                    disposable.dispose()
                }
            }
            OratureSplashScreen(vm)
        }

        composable<OratureHomeRoute> {
            OratureHomeScreen(
                onSettingsClick = { navController.navigate(OratureSettingsRoute) }
            )
        }

        composable<OratureSettingsRoute> {
            // Phase 2 replaces this with the real settings screen.
            OratureHomeScreen(onSettingsClick = {})
        }
    }
}
