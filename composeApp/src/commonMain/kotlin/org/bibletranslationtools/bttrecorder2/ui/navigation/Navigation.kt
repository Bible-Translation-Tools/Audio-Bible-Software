package org.bibletranslationtools.bttrecorder2.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.coroutines.launch
import org.bibletranslationtools.bttrecorder2.ui.screens.MainMenuScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.Project
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectManagementScreen

@Composable
fun Navigation(
    navController: NavHostController
) {
    val scope = rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = MainMenuRoute
    ) {
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