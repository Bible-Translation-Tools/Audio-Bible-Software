package org.bibletranslationtools.otter.common.device

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

class JvmAudioHardwareProvider : AudioHardwareProvider {

    override fun createSink(device: AudioDevice): AudioSink {
        return JvmAudioSink {
            // Match the selected device by name AND output-line capability. macOS exposes a Bluetooth
            // headset (e.g. AirPods) as TWO mixers sharing the same name once the mic engages and it
            // switches to the HFP profile — an input-only one and an output one. Matching by name
            // alone can land on the input mixer, whose getLine(SourceDataLine) throws
            // "Line unsupported: interface SourceDataLine". Filtering by isLineSupported picks the
            // real output mixer, agreeing with how JvmAudioDeviceSelector built the device list.
            val info = DataLine.Info(SourceDataLine::class.java, null)
            val mixer = AudioSystem.getMixerInfo()
                .filter { it.name == device.id }
                .map { AudioSystem.getMixer(it) }
                .firstOrNull { it.isLineSupported(info) }
            (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as SourceDataLine
        }
    }

    override fun createSource(device: AudioDevice): AudioSource {
        return JvmAudioSource {
            // Same as createSink: pick the mixer matching the name that actually provides an INPUT
            // (TargetDataLine), not just the first same-named mixer (which may be output-only).
            val info = DataLine.Info(TargetDataLine::class.java, null)
            val mixer = AudioSystem.getMixerInfo()
                .filter { it.name == device.id }
                .map { AudioSystem.getMixer(it) }
                .firstOrNull { it.isLineSupported(info) }
            (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
        }
    }
}