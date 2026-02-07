package org.bibletranslationtools.recorder2.di

import org.bibletranslationtools.otter.common.device.newaudio.AndroidAudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AndroidAudioHardwareProvider
import org.bibletranslationtools.otter.common.device.newaudio.AndroidAudioSink
import org.bibletranslationtools.otter.common.device.newaudio.AndroidAudioSource
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.newaudio.AudioSink
import org.bibletranslationtools.otter.common.device.newaudio.AudioSource
import org.koin.dsl.module

val androidAudioModule = module {
    // Provide the Android-specific hardware bridges
    single<AudioDeviceSelector> { AndroidAudioDeviceSelector(get()) } // 'get()' provides the Context
    single<AudioHardwareProvider> { AndroidAudioHardwareProvider(get()) }

    // Default dummy instances for startup
    single<AudioSink> { AndroidAudioSink() }
    single<AudioSource> { AndroidAudioSource() }
}