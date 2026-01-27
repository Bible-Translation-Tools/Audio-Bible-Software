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
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectCard
import org.bibletranslationtools.bttrecorder2.ui.screens.MainMenuScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.ProjectManagementContent
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementUiState
import org.bibletranslationtools.bttrecorder2.ui.screens.SplashScreen
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.database.AndroidAppDatabase
import org.koin.android.ext.android.inject
import java.io.File
import javax.inject.Inject

class MainActivity : ComponentActivity() {
    @Inject
    lateinit var directoryProvider: IDirectoryProvider

    val koinDirectoryProvider: IDirectoryProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        lifecycleScope.launch {
            try {
                val db = AndroidAppDatabase(
                    applicationContext,
                    File(koinDirectoryProvider.databaseDirectory, "tr.sqlite"),
                    koinDirectoryProvider
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            App()
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
        workbook = MockData.mockWorkbooks[0],
        onWorkbookClick = {},
        onInfoClick = {},
        onRecordClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun ProjectManagementScreenPreview() {
    ProjectManagementContent(
        uiState = ProjectManagementUiState.Success(MockData.mockWorkbooks),
        onNewProjectClick = {},
        onProjectClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun SplashScreenPreview() {
    SplashScreen()
}