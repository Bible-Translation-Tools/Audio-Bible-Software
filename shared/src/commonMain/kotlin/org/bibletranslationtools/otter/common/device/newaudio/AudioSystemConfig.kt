package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class AudioSystemConfig(
    private val scope: CoroutineScope,
    private val selector: AudioDeviceSelector,
    private val playerFactory: AudioPlayerConnectionFactory,
    private val recorderFactory: AudioRecorderConnectionFactory,
    private val hardwareProvider: AudioHardwareProvider // The platform bridge
) {
    fun start() {
        // 1. Observe Output Device Changes
        selector.activeOutputDevice
            .onEach { device ->
                if (device != null) {
                    // Ask the platform to create the specific hardware handle
                    val sink = hardwareProvider.createSink(device)
                    playerFactory.updateHardwareSink(sink)
                }
            }
            .launchIn(scope)

        // 2. Observe Input Device Changes
        selector.activeInputDevice
            .onEach { device ->
                if (device != null) {
                    val source = hardwareProvider.createSource(device)
                    recorderFactory.updateHardwareSource(source)
                }
            }
            .launchIn(scope)
    }
}