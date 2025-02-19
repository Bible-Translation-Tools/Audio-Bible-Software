package org.bibletranslationtools.recorder2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.bibletranslationtools.bttrecorder2.ui.App
import org.bibletranslationtools.bttrecorder2.ui.components.ProgressPieView
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectCard
import org.bibletranslationtools.bttrecorder2.ui.screens.MainMenuScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.Project
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectManagementScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.SplashScreen
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.database.AndroidAppDatabase
import java.io.File
import javax.inject.Inject

class MainActivity : ComponentActivity() {
    @Inject
    lateinit var directoryProvider: IDirectoryProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        lifecycleScope.launch {
            try {
                val db = AndroidAppDatabase(
                    applicationContext,
                    File(directoryProvider.databaseDirectory, "tr.sqlite"),
                    directoryProvider
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            App((application as Application).appComponent)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
   MainMenuScreen(
       language = {"eng"},
       book = {"gen"},
       onFilesClick = {},
       onRecordClick = {}
   )
}

// Example usage:
@Preview
@Composable
fun ProgessPiePreview() {
    ProgressPieView(
        progress = 65,
        modifier = Modifier.size(48.dp),
        strokeWidth = 0.dp.value, // Example with stroke
        strokeColor = Color.Black,
        progressColor = Color.Green,
        backgroundColor = Color.LightGray
    )
}

@Preview
@Composable
fun ProjectCardPreview() {
    ProjectCard(
        Project(
            language = "English",
            book = "Genesis",
            progress = 65
        ),
        onInfoClick = {},
        onRecordClick = {},
        onProjectClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun ProjectManagementScreenPreview() {
    val sampleProjects = listOf(
        Project("English", "Genesis", 65),
        Project("Spanish", "Exodus", 20),
        Project("French", "Leviticus", 90)
    )
    ProjectManagementScreen(onNewProjectClick = {}, onProjectClick = {}, projects = sampleProjects)
}

@Preview(showBackground = true)
@Composable
fun SplashScreenPreview() {
    SplashScreen()
}