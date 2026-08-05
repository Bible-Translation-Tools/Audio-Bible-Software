package org.bibletranslationtools.shared.di.koin

import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.device.AudioConfig
import org.bibletranslationtools.otter.common.device.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.AudioSink
import org.bibletranslationtools.otter.common.device.AudioSource
import org.bibletranslationtools.otter.common.device.JvmAudioDeviceSelector
import org.bibletranslationtools.otter.common.device.JvmAudioHardwareProvider
import org.bibletranslationtools.otter.common.device.JvmAudioSink
import org.bibletranslationtools.otter.common.device.JvmAudioSource
import org.bibletranslationtools.otter.common.persistence.database.AppDatabase
import org.koin.dsl.module
import java.io.File

// Generic (given an IDirectoryProvider, which each app supplies with its own appName).
val appDatabaseModule = module {
    single<IAppDatabase> {
        val directoryProvider = get<IDirectoryProvider>()
        AppDatabase(
            directoryProvider.databaseDirectory.resolve(File("tr.sqlite")),
            directoryProvider
        )
    }
}

// Desktop audio hardware bridges (identical for every app).
val jvmAudioModule = module {
    single<AudioDeviceSelector> { JvmAudioDeviceSelector() }
    single<AudioHardwareProvider> { JvmAudioHardwareProvider(get()) }
    // A placeholder until AudioSystemConfig routes a real device in; it is never opened, but it is
    // built to the same buffer so nothing depends on which one it got.
    single<AudioSink> { JvmAudioSink(get<AudioConfig>().outputBufferMillis) { null } }
    single<AudioSource> { JvmAudioSource { null } }
}

/** Desktop platform half of the shared Koin graph. Compose with [sharedCommonModules]
 *  plus the app's own directory-provider + ViewModel modules in startKoin. */
val sharedDesktopModules = listOf(appDatabaseModule, jvmAudioModule)
