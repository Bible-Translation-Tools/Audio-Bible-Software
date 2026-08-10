package org.bibletranslationtools.shared.di.koin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bibletranslationtools.otter.common.device.AudioConfig
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioProcessor
import org.bibletranslationtools.otter.common.device.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioSystemConfig
import org.bibletranslationtools.otter.common.device.DefaultAudioProcessor
import org.koin.dsl.module

val commonAudioModule = module {
    single<CoroutineScope> {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    // One instance, read by device discovery, by every recorder start, and by the hardware bridge.
    // That shared identity is what makes the settings actually apply end to end.
    single { AudioConfig() }

    single<AudioProcessor> { DefaultAudioProcessor() }

    // Factories - provided as singletons to manage the workers
    single { AudioPlayerConnectionFactory(get(), get()) }
    single { AudioRecorderConnectionFactory(get()) }

    // Config orchestrator
    single { AudioSystemConfig(get(), get(), get(), get(), get()) }
}