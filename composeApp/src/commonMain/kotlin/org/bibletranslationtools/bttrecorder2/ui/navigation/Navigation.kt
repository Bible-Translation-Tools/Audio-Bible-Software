package org.bibletranslationtools.bttrecorder2.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.coroutines.launch
import org.bibletranslationtools.bttrecorder2.ui.screens.SplashScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.MainMenuScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.Project
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectManagementScreen
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SplashScreenViewModel
import org.bibletranslationtools.otter.common.di.DependencyProvider

@Composable
fun Navigation(
    navController: NavHostController,
    dependencyProvider: DependencyProvider
) {
    val scope = rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = SplashScreenRoute
    ) {
        composable<SplashScreenRoute>() {
            val vm = viewModel { SplashScreenViewModel(dependencyProvider) }
            vm
                .initApp()
                .subscribe {
                    navController.navigate(MainMenuRoute)
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
            val sampleProjects = listOf(
                Project("English", "Genesis", 65),
                Project("Spanish", "Exodus", 20),
                Project("French", "Leviticus", 90)
            )
            ProjectManagementScreen(onNewProjectClick = {}, onProjectClick = {}, projects = sampleProjects)
        }
    }
}