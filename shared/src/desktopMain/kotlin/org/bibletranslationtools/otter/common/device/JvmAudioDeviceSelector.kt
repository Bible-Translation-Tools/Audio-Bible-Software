package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.sound.sampled.*

class JvmAudioDeviceSelector : AudioDeviceSelector {

    private val _activeOutputDevice = MutableStateFlow<AudioDevice?>(null)
    override val activeOutputDevice = _activeOutputDevice.asStateFlow()

    private val _activeInputDevice = MutableStateFlow<AudioDevice?>(null)
    override val activeInputDevice = _activeInputDevice.asStateFlow()

    override fun getOutputDevices(spec: AudioSpec): List<AudioDevice> {
        return discover(SourceDataLine::class.java, spec, AudioDevice.DeviceType.OUTPUT)
    }

    override fun getInputDevices(spec: AudioSpec): List<AudioDevice> {
        return discover(TargetDataLine::class.java, spec, AudioDevice.DeviceType.INPUT)
    }

    override fun selectOutputDevice(device: AudioDevice?) {
        _activeOutputDevice.value = device
    }

    override fun selectInputDevice(device: AudioDevice?) {
        _activeInputDevice.value = device
    }

    private fun discover(
        lineClass: Class<out DataLine>,
        spec: AudioSpec,
        type: AudioDevice.DeviceType
    ): List<AudioDevice> {
        val format = AudioFormat(spec.sampleRate.toFloat(), spec.bitDepth, spec.channels, true, spec.isBigEndian)
        val info = DataLine.Info(lineClass, format)

        return AudioSystem.getMixerInfo().filter { mixerInfo ->
            AudioSystem.getMixer(mixerInfo).isLineSupported(info)
        }.map {
            AudioDevice(id = it.name, name = it.name, type = type)
        }
    }
}