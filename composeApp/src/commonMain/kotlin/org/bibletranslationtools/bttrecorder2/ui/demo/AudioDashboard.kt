package org.bibletranslationtools.bttrecorder2.ui.demo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import java.io.File
import kotlin.let

@Composable
fun AudioDashboard(
    playerFactory: AudioPlayerConnectionFactory,
    selector: AudioDeviceSelector
) {
    val scope = rememberCoroutineScope()

    // UI State for devices
    val activeOutput by selector.activeOutputDevice.collectAsState(null)
    val activeInput by selector.activeInputDevice.collectAsState(null)

    // State for our 3 audio slots
    val fileSlots = remember { mutableStateListOf<AudioFileReader?>(null, null, null) }

    // FileKit Picker Launcher
    val picker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("wav", "mp3")),
        title = "Select Audio File"
    ) { file ->
        file?.let {
            // Note: On JVM, 'it.path' gives us the absolute path for our reader
            val reader = OratureAudioFile(File(it.path)).reader()
            // We'll need a way to track which slot was being updated
            // For simplicity in this snippet, let's assume we update the first empty slot
            val index = fileSlots.indexOf(null).takeIf { i -> i != -1 } ?: 0
            fileSlots[index] = reader as AudioFileReader?
        }
    }

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        Text("Otter Audio Control", style = MaterialTheme.typography.h4)

        Divider(Modifier.padding(vertical = 8.dp))

        // 1. Hardware Selection
        Row {
            DeviceDropdown(
                "Speaker",
                activeOutput,
                selector.getOutputDevices(AudioSpec()) // Simplified spec for discovery
            ) { selector.selectOutputDevice(it) }

            DeviceDropdown(
                "Mic",
                activeInput,
                selector.getInputDevices(AudioSpec())
            ) { selector.selectInputDevice(it) }
        }

        Spacer(Modifier.height(16.dp))

        // 2. Audio Slots
        fileSlots.forEachIndexed { index, reader ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                elevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Slot ${index + 1}", style = MaterialTheme.typography.caption)
                        Text("${reader?.spec?.channels ?: "No file loaded"}", style = MaterialTheme.typography.body1)
                    }

                    if (reader == null) {
                        Button(onClick = { picker.launch() }) {
                            Text("Pick File")
                        }
                    } else {
                        IconButton(onClick = {
                            scope.launch {
                                playerFactory.connect(index, reader, 0)
                                playerFactory.getPlayerWorker().play()
                            }
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                        }

                        IconButton(onClick = {
                            playerFactory.getPlayerWorker().pause()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Pause")
                        }
                    }
                }
            }
        }
    }
}