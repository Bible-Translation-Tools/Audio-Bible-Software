package org.bibletranslationtools.shared.di.koin

import org.bibletranslationtools.otter.common.api.persistence.IAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.device.newaudio.AndroidAudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AndroidAudioHardwareProvider
import org.bibletranslationtools.otter.common.device.newaudio.AndroidAudioSink
import org.bibletranslationtools.otter.common.device.newaudio.AndroidAudioSource
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.newaudio.AudioSink
import org.bibletranslationtools.otter.common.device.newaudio.AudioSource
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
