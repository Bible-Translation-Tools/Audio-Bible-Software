package org.bibletranslationtools.shared.di.koin

import org.bibletranslationtools.otter.common.api.persistence.IAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.newaudio.AudioSink
import org.bibletranslationtools.otter.common.device.newaudio.AudioSource
import org.bibletranslationtools.otter.common.device.newaudio.JvmAudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.JvmAudioHardwareProvider
import org.bibletranslationtools.otter.common.device.newaudio.JvmAudioSink
import org.bibletranslationtools.otter.common.device.newaudio.JvmAudioSource
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
    single<AudioHardwareProvider> { JvmAudioHardwareProvider() }
    single<AudioSink> { JvmAudioSink { null } }
    single<AudioSource> { JvmAudioSource { null } }
}

/** Desktop platform half of the shared Koin graph. Compose with [sharedCommonModules]
 *  plus the app's own directory-provider + ViewModel modules in startKoin. */
val sharedDesktopModules = listOf(appDatabaseModule, jvmAudioModule)
