package org.bibletranslationtools.otter.common.device

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

class JvmAudioHardwareProvider(
    /**
     * The engine settings the created hardware is built to. Only [AudioConfig.outputBufferMillis] is
     * used here: the format a sink opens at is the take's, decided at `load()`, but the buffer depth is
     * the engine's and has to be handed over at construction. Before this the sink was built with its
     * own default and nothing could reach it.
     */
    private val config: AudioConfig = AudioConfig()
) : AudioHardwareProvider {

    override fun createSink(device: AudioDevice): AudioSink {
        return JvmAudioSink(config.outputBufferMillis) {
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