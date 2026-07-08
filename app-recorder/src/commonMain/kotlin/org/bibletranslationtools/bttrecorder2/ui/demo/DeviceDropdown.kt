package org.bibletranslationtools.bttrecorder2.ui.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.otter.common.device.newaudio.AudioDevice

@Composable
fun DeviceDropdown(
    label: String,
    selectedDevice: AudioDevice?,
    availableDevices: List<AudioDevice>,
    onSelect: (AudioDevice) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(8.dp)) {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: ${selectedDevice?.name ?: "Select Device"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            availableDevices.forEach { device ->
                DropdownMenuItem(onClick = {
                    onSelect(device)
                    expanded = false
                }) {
                    Text(device.name)
                }
            }
            if (availableDevices.isEmpty()) {
                DropdownMenuItem(onClick = {}, enabled = false) {
                    Text("No devices found")
                }
            }
        }
    }
}