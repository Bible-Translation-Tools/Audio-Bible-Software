package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.flow.Flow

/**
 * Represents a hardware audio device (Input or Output).
 */
data class AudioDevice(
    val id: String,    // Platform specific ID (Mixer name or Android ID)
    val name: String,  // User-friendly display name
    val type: DeviceType
) {
    enum class DeviceType { INPUT, OUTPUT }
}

interface AudioDeviceSelector {
    // Reactive streams for the UI to subscribe to
    val activeOutputDevice: Flow<AudioDevice?>
    val activeInputDevice: Flow<AudioDevice?>

    // Device Discovery
    fun getOutputDevices(spec: AudioSpec): List<AudioDevice>
    fun getInputDevices(spec: AudioSpec): List<AudioDevice>

    // Selection
    fun selectOutputDevice(device: AudioDevice?)
    fun selectInputDevice(device: AudioDevice?)
}