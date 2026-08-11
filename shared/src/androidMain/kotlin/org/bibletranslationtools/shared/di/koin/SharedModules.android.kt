package org.bibletranslationtools.shared.di.koin

import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.device.AndroidAudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AndroidAudioHardwareProvider
import org.bibletranslationtools.otter.common.device.AndroidAudioSink
import org.bibletranslationtools.otter.common.device.AndroidAudioSource
import org.bibletranslationtools.otter.common.device.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.AudioSink
import org.bibletranslationtools.otter.common.device.AudioSource
import org.bibletranslationtools.otter.database.AndroidAppDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

// Generic (given an IDirectoryProvider + the android Context, which the app's startKoin
// supplies via androidContext()).
val appDatabaseModule = module {
    single<IAppDatabase> {
        val directoryProvider = get<IDirectoryProvider>()
        AndroidAppDatabase(
            androidContext(),
            directoryProvider.databaseDirectory.resolve(File("tr.sqlite")),
            directoryProvider
        )
    }
}

// Android audio hardware bridges (identical for every app). `get()` resolves the
// android Context bound by androidContext() in the app's startKoin.
val androidAudioModule = module {
    single<AudioDeviceSelector> { AndroidAudioDeviceSelector(get()) }
    single<AudioHardwareProvider> { AndroidAudioHardwareProvider(get()) }
    single<AudioSink> { AndroidAudioSink() }
    single<AudioSource> { AndroidAudioSource() }
}

/** Android platform half of the shared Koin graph. Compose with [sharedCommonModules]
 *  plus the app's own directory-provider + ViewModel modules in startKoin. */
val sharedAndroidModules = listOf(appDatabaseModule, androidAudioModule)
