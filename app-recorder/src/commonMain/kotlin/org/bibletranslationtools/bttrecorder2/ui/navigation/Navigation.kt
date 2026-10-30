package org.bibletranslationtools.bttrecorder2.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.bttrecorder2.ui.screens.BookListScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.ChapterListScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.MainMenuScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.PlaybackScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectManagementScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectWizardScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.RecorderScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.SettingsScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.SplashScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.UnitListScreen
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportProjectViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.MainMenuViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.PlaybackViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.RecorderViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SplashScreenViewModel
import org.koin.mp.KoinPlatform.getKoin

@Composable
fun Navigation(
    navController: NavHostController
) {
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
            // Collect active-nav in composition so Record navigates on the click path
            // (no deferred coroutine awaiting prefs).
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
                onNewProjectClick = { navController.navigate(ProjectWizardRoute) },
                onTypeClick = { groupId, type -> navController.navigate(BookListRoute(groupId, type)) },
                onSettingsClick = { navController.navigate(SettingsRoute) }
            )
        }

        composable<BookListRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<BookListRoute>()
            val vm = viewModel { ProjectManagementViewModel() }
            BookListScreen(
                viewModel = vm,
                groupId = route.groupId,
                type = route.type,
                onBackClick = { navController.popBackStack() },
                onNewProjectClick = { navController.navigate(ProjectWizardRoute) },
                onProjectOpened = { navController.navigate(ChapterListRoute) },
                onSettingsClick = { navController.navigate(SettingsRoute) }
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<ChapterListRoute> {
            ChapterListScreen(
                onBackClick = { navController.popBackStack() },
                onLanguagesClick = { navController.popBackStack(ProjectManagementRoute, inclusive = false) },
                onNewProjectClick = { navController.navigate(ProjectWizardRoute) },
                onSettingsClick = { navController.navigate(SettingsRoute) },
                onChapterClick = { navController.navigate(UnitListRoute) },
                onRecordChapter = { sourceId, targetId, chapterSort ->
                    navController.navigate(
                        RecorderRoute(
                            sourceId = sourceId,
                            targetId = targetId,
                            chapterNumber = chapterSort,
                            unitNumber = -1
                        )
                    )
                },
                onOpenChapterPlayback = { sourceId, targetId, chapterSort, takeNumber ->
                    navController.navigate(
                        PlaybackRoute(
                            sourceId = sourceId,
                            targetId = targetId,
                            chapterNumber = chapterSort,
                            unitNumber = -1,       // chapter target (loadTarget maps -1 → whole chapter)
                            takeNumber = takeNumber
                        )
                    )
                }
            )
        }

        composable<UnitListRoute> {
            UnitListScreen(
                onBackClick = { navController.popBackStack() },
                onLanguagesClick = { navController.popBackStack(ProjectManagementRoute, inclusive = false) },
                onBooksClick = { navController.popBackStack<BookListRoute>(inclusive = false) },
                onNewProjectClick = { navController.navigate(ProjectWizardRoute) },
                onSettingsClick = { navController.navigate(SettingsRoute) },
                onUnitClick = { sourceId, targetId, chapterSort, unitSort ->
                    navController.navigate(
                        RecorderRoute(
                            sourceId = sourceId,
                            targetId = targetId,
                            chapterNumber = chapterSort,
                            unitNumber = unitSort
                        )
                    )
                },
                onOpenPlayback = { sourceId, targetId, chapterSort, unitSort, takeNumber ->
                    navController.navigate(
                        PlaybackRoute(
                            sourceId = sourceId,
                            targetId = targetId,
                            chapterNumber = chapterSort,
                            unitNumber = unitSort,
                            takeNumber = takeNumber
                        )
                    )
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
            val vm: RecorderViewModel = viewModel { koin.get() }

            LaunchedEffect(sourceId, targetId, chapterNumber, unitNumber) {
                vm.loadTarget(
                    sourceId = sourceId,
                    targetId = targetId,
                    chapterNumber = chapterNumber,
                    unitNumber = unitNumber
                )
            }

            RecorderScreen(
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
