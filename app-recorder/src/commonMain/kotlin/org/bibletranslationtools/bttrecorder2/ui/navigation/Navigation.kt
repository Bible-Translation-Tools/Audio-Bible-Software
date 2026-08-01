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
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.bttrecorder2.ui.screens.ChapterListScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.MainMenuScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.PlaybackScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectManagementScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectWizardScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.SplashScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.UnitListScreen
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportProjectViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.MainMenuViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.PlaybackViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SplashScreenViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.toRoute
import org.koin.mp.KoinPlatform.getKoin

@Composable
fun Navigation(
    navController: NavHostController
) {
    val scope = rememberCoroutineScope()
    val appPreferences = remember { getKoin().get<IAppPreferences>() }
    val exportViewModel = remember { getKoin().get<ExportProjectViewModel>() }

    NavHost(
        navController = navController,
        startDestination = SplashScreenRoute
    ) {
        composable<SplashScreenRoute> {
            val vm = viewModel { SplashScreenViewModel() }
            LaunchedEffect(Unit) {
                // Keep navigate inside composition (not Handler.post). Posting navigate onto the
                // main looper outside NavHost sync leaves entries in INITIALIZED and crashes on
                // Activity destroy: "State must be at least CREATED to be moved to DESTROYED".
                vm.initApp().await()
                navController.navigate(MainMenuRoute) {
                    popUpTo(SplashScreenRoute) { inclusive = true }
                }
            }
            SplashScreen()
        }

        composable<MainMenuRoute> {
            val vm = viewModel { MainMenuViewModel() }
            // Read nav from StateFlow in composition so Record navigates synchronously.
            // scope.launch + navState.first() often never runs under Android Compose UI tests
            // (test dispatcher / idling), leaving the home screen stuck after splash.
            val nav by vm.navState.collectAsState()
            MainMenuScreen(
                viewModel = vm,
                onFilesClick = {
                    navController.navigate(ProjectManagementRoute)
                },
                onRecordClick = {
                    when {
                        nav.hasActiveUnit -> navController.navigate(
                            RecorderRoute(nav.workbookSourceId, nav.workbookTargetId, nav.chapterSort, nav.unitSort)
                        )
                        nav.hasActiveChapter -> navController.navigate(
                            RecorderRoute(nav.workbookSourceId, nav.workbookTargetId, nav.chapterSort, -1)
                        )
                        nav.hasActiveWorkbook -> navController.navigate(
                            RecorderRoute(nav.workbookSourceId, nav.workbookTargetId, -1, -1)
                        )
                        else -> navController.navigate(ProjectManagementRoute)
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
                    // Persist active workbook off the UI path; navigate immediately so
                    // Compose UI tests (and users) are not blocked on prefs I/O or a
                    // deferred coroutine dispatcher.
                    scope.launch {
                        appPreferences.setActiveWorkbook(
                            workbookDesc.sourceCollection.id,
                            workbookDesc.targetCollection.id
                        )
                    }
                    navController.navigate(ChapterListRoute)
                },
                onRecordClick = { workbookDesc ->
                    // Project-list mic: jump straight into the recorder at the
                    // project's first verse (matching the original's per-project
                    // record button), instead of drilling through chapter/unit lists.
                    scope.launch {
                        appPreferences.setActiveWorkbook(
                            workbookDesc.sourceCollection.id,
                            workbookDesc.targetCollection.id
                        )
                    }
                    navController.navigate(
                        RecorderRoute(
                            workbookDesc.sourceCollection.id,
                            workbookDesc.targetCollection.id,
                            -1,
                            -1
                        )
                    )
                },
                onSettingsClick = { navController.navigate(SettingsRoute) }
            )
        }

        composable<SettingsRoute> {
            org.bibletranslationtools.bttrecorder2.ui.screens.SettingsScreen(
                onBackClick = { navController.popBackStack() }
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
                },
                onOpenChapterPlayback = { chapterSort, takeNumber ->
                    scope.launch {
                        val nav = appPreferences.navState.first()
                        navController.navigate(
                            PlaybackRoute(
                                sourceId = nav.workbookSourceId,
                                targetId = nav.workbookTargetId,
                                chapterNumber = chapterSort,
                                unitNumber = -1,       // chapter target (loadTarget maps -1 → whole chapter)
                                takeNumber = takeNumber
                            )
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

            // Block entry into the recorder if the user is currently exporting
            // a backup for this exact workbook. Allowing recording would mean
            // writing a new take into a directory the exporter is actively
            // walking — risking a half-zipped file in the .orature archive.
            val exportingRoute by exportViewModel.exportingRoute.collectAsState()
            val isThisWorkbookExporting = exportingRoute == (sourceId to targetId)

            if (isThisWorkbookExporting) {
                RecorderBlockedByExportScreen(
                    onBackClick = { navController.popBackStack() }
                )
                return@composable
            }

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
                    // Replace the existing PM entry so LaunchedEffect reloads workbooks
                    // and the new project is visible immediately.
                    navController.navigate(ProjectManagementRoute) {
                        popUpTo(ProjectManagementRoute) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}

/**
 * Shown in place of the Recorder screen when the user navigates into a project
 * whose backup is currently in flight. Recording during export would race the
 * file walk in [BackupProjectExporter.copyTakeFiles], so we lock the user out
 * until the export resolves.
 */
@Composable
private fun RecorderBlockedByExportScreen(onBackClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text("This project is being backed up.")
            Text(
                text = "Recording is paused until the backup finishes.",
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(onClick = onBackClick, modifier = Modifier.padding(top = 16.dp)) {
                Text("Back")
            }
        }
    }
}
