package org.bibletranslationtools.otter.common.device

interface AudioHardwareProvider {
    fun createSink(device: AudioDevice): AudioSink
    fun createSource(device: AudioDevice): AudioSource
}