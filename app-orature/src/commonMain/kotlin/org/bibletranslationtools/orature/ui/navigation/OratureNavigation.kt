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
import org.bibletranslationtools.orature.ui.viewmodels.OratureHomeViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureProjectWizardViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureSplashViewModel
import org.koin.mp.KoinPlatform.getKoin

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
            val vm = viewModel { getKoin().get<OratureHomeViewModel>() }
            // On create, the home screen collects the wizard's projectCreated signal and
            // reloads + reselects the new group itself (OratureHomeScreen), so the wizard's
            // onComplete hook is left at its no-op default (only the unit tests use it).
            val wizardVm = viewModel {
                OratureProjectWizardViewModel()
            }
            OratureHomeScreen(
                viewModel = vm,
                wizardViewModel = wizardVm,
                onBookClick = { book ->
                    // Phase 4 opens the project's chapter/verse view. Stub for now —
                    // the VM already logs the tap; nav wiring is a no-op until then.
                },
                onImportClick = {
                    // Phase 9 wires up project import. Stub for now.
                }
                // Settings/Info are now left drawers hosted inside the home screen
                // (Phase 2), toggled by the nav rail — no longer separate routes.
            )
        }
    }
}
