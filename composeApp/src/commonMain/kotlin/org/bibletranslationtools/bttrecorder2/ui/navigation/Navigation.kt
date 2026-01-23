package org.bibletranslationtools.bttrecorder2.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.coroutines.launch
import org.bibletranslationtools.bttrecorder2.ui.screens.ChapterListScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.MainMenuScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectManagementScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.SplashScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.UnitListScreen
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SplashScreenViewModel
import androidx.navigation.toRoute


@Composable
fun Navigation(
    navController: NavHostController
) {
    val scope = rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = SplashScreenRoute
    ) {
        composable<SplashScreenRoute>() {
            val vm = viewModel { SplashScreenViewModel() }
            LaunchedEffect(Unit) {
                val disposable = vm
                    .initApp()
                    .subscribe {
                        launch {
                            navController.navigate(MainMenuRoute) {
                                popUpTo(SplashScreenRoute) {
                                    inclusive = true
                                }
                            }
                        }
                    }
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    disposable.dispose()
                }
            }
            SplashScreen()
        }
        composable<MainMenuRoute> {
            MainMenuScreen(
                language = { "eng" },
                book = { "gen" },
                onFilesClick = {
                    scope.launch {
                        navController.navigate(ProjectManagementRoute)
                    }
                },
                onRecordClick = {}
            )
        }
        composable<ProjectManagementRoute> {
            val vm = viewModel { ProjectManagementViewModel() }
            ProjectManagementScreen(
                viewModel = vm,
                onNewProjectClick = { vm.onNewProjectClick() },
                onProjectClick = { workbook ->
                    navController.navigate(ChapterListRoute(workbook.id))
                }
            )
        }
        composable<ChapterListRoute> { backStackEntry ->
            val route: ChapterListRoute = backStackEntry.toRoute()
            val workbook = MockData.mockWorkbooks.find { it.id == route.workbookId } ?: MockData.mockWorkbooks[0]
            ChapterListScreen(
                workbook = workbook,
                onBackClick = { navController.popBackStack() },
                onChapterClick = { chapter ->
                    navController.navigate(UnitListRoute(workbook.slug, chapter))
                }
            )
        }
        composable<UnitListRoute> { backStackEntry ->
            val route: UnitListRoute = backStackEntry.toRoute()
            val workbook = MockData.mockWorkbooks.find { it.slug == route.projectName } ?: MockData.mockWorkbooks[0]
            UnitListScreen(
                workbook = workbook,
                chapterNumber = route.chapterNumber,
                onBackClick = { navController.popBackStack() },
                onUnitClick = { unit ->
                    // TODO: Navigate to Recording screen
                }
            )
        }
    }
}