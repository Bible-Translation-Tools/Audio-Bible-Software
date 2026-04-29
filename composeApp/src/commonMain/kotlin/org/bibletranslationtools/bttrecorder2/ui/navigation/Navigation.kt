package org.bibletranslationtools.bttrecorder2.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bibletranslationtools.bttrecorder2.preferences.IAppPreferences
import org.bibletranslationtools.bttrecorder2.ui.screens.ChapterListScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.MainMenuScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.PlaybackScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectManagementScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectWizardScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.SplashScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.UnitListScreen
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.MainMenuViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.PlaybackViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SplashScreenViewModel
import androidx.navigation.toRoute
import org.koin.mp.KoinPlatform.getKoin

@Composable
fun Navigation(
    navController: NavHostController
) {
    val scope = rememberCoroutineScope()
    val appPreferences = remember { getKoin().get<IAppPreferences>() }

    NavHost(
        navController = navController,
        startDestination = SplashScreenRoute
    ) {
        composable<SplashScreenRoute> {
            val vm = viewModel { SplashScreenViewModel() }
            LaunchedEffect(Unit) {
                val disposable = vm
                    .initApp()
                    .subscribe {
                        launch {
                            navController.navigate(MainMenuRoute) {
                                popUpTo(SplashScreenRoute) { inclusive = true }
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
            val vm = viewModel { MainMenuViewModel() }
            MainMenuScreen(
                viewModel = vm,
                onFilesClick = {
                    scope.launch {
                        navController.navigate(ProjectManagementRoute)
                    }
                },
                onRecordClick = {
                    scope.launch {
                        val nav = appPreferences.navState.first()
                        when {
                            nav.hasActiveUnit -> navController.navigate(
                                RecorderRoute(nav.workbookSourceId, nav.workbookTargetId, nav.chapterSort, nav.unitSort)
                            )
                            nav.hasActiveChapter -> navController.navigate(UnitListRoute)
                            nav.hasActiveWorkbook -> navController.navigate(ChapterListRoute)
                            else -> navController.navigate(ProjectManagementRoute)
                        }
                    }
                }
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
                    scope.launch {
                        appPreferences.setActiveWorkbook(
                            workbookDesc.sourceCollection.id,
                            workbookDesc.targetCollection.id
                        )
                        navController.navigate(ChapterListRoute)
                    }
                }
            )
        }

        composable<ChapterListRoute> {
            ChapterListScreen(
                onBackClick = { navController.popBackStack() },
                onChapterClick = { chapterSort ->
                    scope.launch {
                        appPreferences.setActiveChapter(chapterSort)
                        navController.navigate(UnitListRoute)
                    }
                },
                onRecordChapter = { chapterSort ->
                    scope.launch {
                        appPreferences.setActiveChapter(chapterSort)
                        val nav = appPreferences.navState.first()
                        navController.navigate(
                            RecorderRoute(nav.workbookSourceId, nav.workbookTargetId, chapterSort, -1)
                        )
                    }
                }
            )
        }

        composable<UnitListRoute> {
            UnitListScreen(
                onBackClick = { navController.popBackStack() },
                onUnitClick = { unitSort ->
                    scope.launch {
                        val nav = appPreferences.navState.first()
                        appPreferences.setActiveUnit(unitSort)
                        navController.navigate(
                            RecorderRoute(nav.workbookSourceId, nav.workbookTargetId, nav.chapterSort, unitSort)
                        )
                    }
                },
                onRecordChapter = {
                    scope.launch {
                        val nav = appPreferences.navState.first()
                        navController.navigate(
                            RecorderRoute(nav.workbookSourceId, nav.workbookTargetId, nav.chapterSort, -1)
                        )
                    }
                },
                onOpenPlayback = { unitSort, takeNumber ->
                    scope.launch {
                        val nav = appPreferences.navState.first()
                        navController.navigate(
                            PlaybackRoute(
                                sourceId = nav.workbookSourceId,
                                targetId = nav.workbookTargetId,
                                chapterNumber = nav.chapterSort,
                                unitNumber = unitSort,
                                takeNumber = takeNumber
                            )
                        )
                    }
                }
            )
        }

        composable<RecorderRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<RecorderRoute>()

            val sourceId = route.sourceId
            val targetId = route.targetId
            val chapterNumber = route.chapterNumber
            val unitNumArg = route.unitNumber
            val unitNumber = if (unitNumArg == -1) null else unitNumArg

            val koin = getKoin()
            val vm: org.bibletranslationtools.bttrecorder2.ui.viewmodels.RecorderViewModel = viewModel { koin.get() }

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
                onNavigateToPlayback = { takeNumber ->
                    navController.navigate(
                        PlaybackRoute(
                            sourceId = sourceId,
                            targetId = targetId,
                            chapterNumber = chapterNumber,
                            unitNumber = unitNumArg,
                            takeNumber = takeNumber
                        )
                    ) {
                        popUpTo<RecorderRoute> { inclusive = true }
                    }
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<PlaybackRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<PlaybackRoute>()
            val koin = getKoin()
            val vm: PlaybackViewModel = viewModel { koin.get() }

            LaunchedEffect(
                route.sourceId,
                route.targetId,
                route.chapterNumber,
                route.unitNumber,
                route.takeNumber
            ) {
                vm.loadTarget(
                    sourceId = route.sourceId,
                    targetId = route.targetId,
                    chapterNumber = route.chapterNumber,
                    unitNumber = route.unitNumber,
                    takeNumber = route.takeNumber
                )
            }

            PlaybackScreen(
                viewModel = vm,
                onBackClick = { navController.popBackStack() },
                onNavigateToRecorder = { sourceId, targetId, chapterNumber, unitNumber ->
                    navController.navigate(
                        RecorderRoute(
                            sourceId = sourceId,
                            targetId = targetId,
                            chapterNumber = chapterNumber,
                            unitNumber = unitNumber
                        )
                    )
                }
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
