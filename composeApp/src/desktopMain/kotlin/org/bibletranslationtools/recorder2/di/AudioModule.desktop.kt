package org.bibletranslationtools.recorder2.di

import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.newaudio.AudioProcessor
import org.bibletranslationtools.otter.common.device.newaudio.AudioSink
import org.bibletranslationtools.otter.common.device.newaudio.AudioSource
import org.bibletranslationtools.otter.common.device.newaudio.JvmAudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.JvmAudioHardwareProvider
import org.bibletranslationtools.otter.common.device.newaudio.JvmAudioProcessor
import org.bibletranslationtools.otter.common.device.newaudio.JvmAudioSink
import org.bibletranslationtools.otter.common.device.newaudio.JvmAudioSource
import org.koin.dsl.module

val jvmAudioModule = module {
    single<AudioDeviceSelector> { JvmAudioDeviceSelector() }
    single<AudioHardwareProvider> { JvmAudioHardwareProvider() }
    single<AudioProcessor> { JvmAudioProcessor() }

    // Initial dummy sink/source for factory startup
    single<AudioSink> { JvmAudioSink { null } }
    single<AudioSource> { JvmAudioSource { null } }
}

