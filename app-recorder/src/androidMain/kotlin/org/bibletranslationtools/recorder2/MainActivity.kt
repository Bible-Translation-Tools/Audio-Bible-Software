package org.bibletranslationtools.recorder2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import org.bibletranslationtools.bttrecorder2.di.koin.commonAudioModule
import org.bibletranslationtools.bttrecorder2.ui.demo.AudioDashboard
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.device.newaudio.AudioSystemConfig
import org.bibletranslationtools.recorder2.di.androidAudioModule
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.io.File

class MainActivity : ComponentActivity() {

    val koinDirectoryProvider: IDirectoryProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Opt in to edge-to-edge so the system status bar + 3-button nav bar
        // become transparent. The Compose root (see App.kt) then applies
        // safeDrawingPadding() so UI content stays clear of those bars while
        // the app background paints under them. Without this call, on Android
        // versions/devices using gesture nav we'd letterbox; with 3-button nav
        // the system inset is non-zero and our content would otherwise overlap.
        enableEdgeToEdge()
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
            // Ask for RECORD_AUDIO at runtime if not already granted; AudioRecord
            // construction silently fails its STATE_INITIALIZED check otherwise,
            // surfacing as "Failed to initialize AudioRecord" in AndroidAudioSource.
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* If denied, AudioRecord.open() will throw when the user tries to record. */ }
            LaunchedEffect(Unit) {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            App()
        }
    }
}