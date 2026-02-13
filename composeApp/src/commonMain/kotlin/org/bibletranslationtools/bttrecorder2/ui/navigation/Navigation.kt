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
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectWizardScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.SplashScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.UnitListScreen
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SplashScreenViewModel
import androidx.navigation.toRoute


import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.koin.mp.KoinPlatform.getKoin

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
                onNewProjectClick = {
                    navController.navigate(ProjectWizardRoute)
                },
                onProjectClick = { workbookDesc ->
                    navController.navigate(ChapterListRoute(workbookDesc.sourceCollection.id, workbookDesc.targetCollection.id))
                }
            )
        }
        composable<ChapterListRoute> { backStackEntry ->
            val route: ChapterListRoute = backStackEntry.toRoute()
            ChapterListScreen(
                workbookSourceId = route.workbookSourceId,
                workbookTargetId = route.workbookTargetId,
                onBackClick = { navController.popBackStack() },
                onChapterClick = { chapter ->
                    navController.navigate(UnitListRoute(route.workbookSourceId, route.workbookTargetId, chapter))
                },
                onCompileClick = { chapterId ->
                    // TODO: Implement compilation logic
                    println("Compile clicked for chapter $chapterId")
                }
            )
        }
        composable<UnitListRoute> { backStackEntry ->
            val route: UnitListRoute = backStackEntry.toRoute()
            UnitListScreen(
                workbookSourceId = route.workbookSourceId,
                workbookTargetId = route.workbookTargetId,
                chapterNumber = route.chapterNumber,
                onBackClick = { navController.popBackStack() },
                onUnitClick = { unitSort ->
                    navController.navigate(RecorderRoute(route.workbookSourceId,route.workbookTargetId,route.chapterNumber, unitSort))
                },
                onRecordChapter = {
                   navController.navigate(RecorderRoute(route.workbookSourceId,route.workbookTargetId,route.chapterNumber, -1))
                }
            )
        }
        composable<RecorderRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<RecorderRoute>()

            val sourceId = route.sourceId?: 0
            val targetId = route.targetId
            val chapterNumber = route.chapterNumber
            val unitNumArg = route.unitNumber
            val unitNumber = if (unitNumArg == -1) null else unitNumArg

            // Using koinViewModel to get the ViewModel with dependencies injected
            val koin = getKoin()
            val vm: org.bibletranslationtools.bttrecorder2.ui.viewmodels.RecorderViewModel = viewModel { koin.get() }
            
            // Initial load
            LaunchedEffect(sourceId, targetId, chapterNumber, unitNumber) {
                vm.loadTarget(
                    sourceId = sourceId,
                    targetId = targetId,
                    chapterNumber = chapterNumber,
                    unitNumber = unitNumber
                )
            }
            
            org.bibletranslationtools.bttrecorder2.ui.screens.RecorderScreen(
                viewModel = vm,
                onBackClick = { navController.popBackStack() }
            )
        }
        composable<ProjectWizardRoute> {
            val vm = viewModel { ProjectCreationViewModel() }
            ProjectWizardScreen(
                viewModel = vm,
                onBackClick = { navController.popBackStack() },
                onProjectCreated = { 
                    navController.popBackStack() 
                }
            )
        }
    }
}