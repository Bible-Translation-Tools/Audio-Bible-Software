package org.bibletranslationtools.otter.common.device

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidAudioDeviceSelector(private val context: Context) : AudioDeviceSelector {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _activeOutputDevice = MutableStateFlow<AudioDevice?>(null)
    override val activeOutputDevice = _activeOutputDevice.asStateFlow()

    private val _activeInputDevice = MutableStateFlow<AudioDevice?>(null)
    override val activeInputDevice = _activeInputDevice.asStateFlow()

    override fun getOutputDevices(spec: AudioSpec): List<AudioDevice> {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { it.toCommonAudioDevice(AudioDevice.DeviceType.OUTPUT) }
    }

    override fun getInputDevices(spec: AudioSpec): List<AudioDevice> {
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .map { it.toCommonAudioDevice(AudioDevice.DeviceType.INPUT) }
    }

    override fun selectOutputDevice(device: AudioDevice?) {
        _activeOutputDevice.value = device
        // On Android, simply selecting the device in our flow doesn't
        // route the audio. Routing is handled by the Communication strategy
        // or by passing the device to the AudioTrack/AudioRecord in the HardwareProvider.
    }

    override fun selectInputDevice(device: AudioDevice?) {
        _activeInputDevice.value = device
    }

    /**
     * Helper to convert Android's AudioDeviceInfo to our Common data class.
     * We use the product name and ID for display.
     */
    private fun AudioDeviceInfo.toCommonAudioDevice(type: AudioDevice.DeviceType): AudioDevice {
        val label = if (productName.isNullOrBlank()) {
            // Fallback to type name if product name is unavailable
            getDeviceTypeName(this.type)
        } else {
            productName.toString()
        }

        return AudioDevice(
            id = id.toString(), // The unique Android system ID for the device
            name = label,
            type = type
        )
    }

    private fun getDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
        else -> "Unknown Device"
    }
}