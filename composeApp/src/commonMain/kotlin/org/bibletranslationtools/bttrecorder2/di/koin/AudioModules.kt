package org.bibletranslationtools.bttrecorder2.di.koin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioProcessor
import org.bibletranslationtools.otter.common.device.newaudio.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioSystemConfig
import org.bibletranslationtools.otter.common.device.newaudio.DefaultAudioProcessor
import org.koin.dsl.module

val commonAudioModule = module {
    single<CoroutineScope> {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    single<AudioProcessor> { DefaultAudioProcessor() }

    // Factories - provided as singletons to manage the workers
    single { AudioPlayerConnectionFactory(get(), get()) }
    single { AudioRecorderConnectionFactory(get()) }

    // Config orchestrator
    single { AudioSystemConfig(get(), get(), get(), get(), get()) }
}