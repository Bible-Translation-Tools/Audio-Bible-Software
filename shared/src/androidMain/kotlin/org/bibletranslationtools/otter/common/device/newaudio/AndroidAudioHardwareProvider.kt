package org.bibletranslationtools.otter.common.device.newaudio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

class AndroidAudioHardwareProvider(private val context: Context) : AudioHardwareProvider {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun createSink(device: AudioDevice): AudioSink {
        val sink = AndroidAudioSink()

        // Find the actual Android Device Info object matching our ID
        val androidDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .find { it.id.toString() == device.id }

        // We wrap the sink setup to ensure that once the track is opened,
        // it is immediately routed to the correct hardware ID.
        return object : AudioSink by sink {
            override fun open(spec: AudioSpec) {
                sink.open(spec)
                // This is the Android-specific routing call
                sink.getAudioTrack()?.preferredDevice = androidDevice
            }
        }
    }

    override fun createSource(device: AudioDevice): AudioSource {
        val source = AndroidAudioSource()

        val androidDevice = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .find { it.id.toString() == device.id }

        return object : AudioSource by source {
            override fun open(spec: AudioSpec) {
                source.open(spec)
                // Route the recording capture to the specific mic ID
                source.getAudioRecord()?.preferredDevice = androidDevice
            }
        }
    }
}