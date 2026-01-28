package org.bibletranslationtools.otter.common.device.newaudio

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

class JvmAudioHardwareProvider : AudioHardwareProvider {

    override fun createSink(device: AudioDevice): AudioSink {
        return JvmAudioSink {
            val mixerInfo = AudioSystem.getMixerInfo().find { it.name == device.id }
            val mixer = if (mixerInfo != null) AudioSystem.getMixer(mixerInfo) else null

            // We return a SourceDataLine from the specific mixer
            val info = DataLine.Info(SourceDataLine::class.java, null)
            (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as SourceDataLine
        }
    }

    override fun createSource(device: AudioDevice): AudioSource {
        return JvmAudioSource {
            val mixerInfo = AudioSystem.getMixerInfo().find { it.name == device.id }
            val mixer = if (mixerInfo != null) AudioSystem.getMixer(mixerInfo) else null

            // We return a TargetDataLine (Recording) from the specific mixer
            val info = DataLine.Info(TargetDataLine::class.java, null)
            (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
        }
    }
}