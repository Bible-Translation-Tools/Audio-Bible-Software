package org.bibletranslationtools.orature.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.bibletranslationtools.orature.ui.screens.OratureHomeScreen
import org.bibletranslationtools.orature.ui.screens.OratureNarrationScreen
import org.bibletranslationtools.orature.ui.screens.OratureSplashScreen
import org.bibletranslationtools.orature.ui.screens.OratureTranslationScreen
import org.bibletranslationtools.orature.ui.screens.OratureVerseMarkerScreen
import org.bibletranslationtools.orature.ui.viewmodels.OratureVerseMarkerViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureHomeViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureNarrationViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureProjectWizardViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureSplashViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureTranslationViewModel
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.koin.mp.KoinPlatform.getKoin

@Composable
fun OratureNavigation(navController: NavHostController, startWithSplash: Boolean = true) {
    val scope = rememberCoroutineScope()

    // Desktop runs the branded splash in its own dedicated window (see main.kt) and starts the main
    // window straight at Home; Android has no separate window, so the splash is the first route.
    NavHost(
        navController = navController,
        startDestination = if (startWithSplash) OratureSplashRoute else OratureHomeRoute
    ) {
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
                    // Open the project's mode page (JVM: openWorkbook docks the mode page).
                    // NARRATION/DIALECT → narration shell; TRANSLATION → translation shell.
                    if (book.mode == ProjectMode.TRANSLATION) {
                        navController.navigate(OratureTranslationRoute(book.id))
                    } else {
                        navController.navigate(OratureNarrationRoute(book.id))
                    }
                }
                // The project-import modal is hosted inside the home screen (opened by its import
                // button). Settings/Info are left drawers hosted by the shell, toggled by the rail.
            )
        }

        composable<OratureNarrationRoute> { entry ->
            val route = entry.toRoute<OratureNarrationRoute>()
            val vm = viewModel(key = "narration-${route.workbookDescriptorId}") {
                OratureNarrationViewModel(route.workbookDescriptorId)
            }
            OratureNarrationScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenVerseMarkerEditor = { navController.navigate(OratureVerseMarkerRoute) }
            )
        }

        // The built-in Verse Marker editor (JVM: the standalone marker plugin, built in). Its inputs
        // are handed off via the OratureVerseMarkerEditor singleton before navigation; the VM reads
        // them on open. Closing (save or cancel) pops back to the launching mode page, which reloads.
        composable<OratureVerseMarkerRoute> {
            val vm = viewModel { OratureVerseMarkerViewModel() }
            OratureVerseMarkerScreen(
                viewModel = vm,
                onClose = { navController.popBackStack() }
            )
        }

        composable<OratureTranslationRoute> { entry ->
            val route = entry.toRoute<OratureTranslationRoute>()
            val vm = viewModel(key = "translation-${route.workbookDescriptorId}") {
                OratureTranslationViewModel(route.workbookDescriptorId)
            }
            OratureTranslationScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onGoToNarration = { navController.navigate(OratureNarrationRoute(route.workbookDescriptorId)) }
            )
        }
    }
}
