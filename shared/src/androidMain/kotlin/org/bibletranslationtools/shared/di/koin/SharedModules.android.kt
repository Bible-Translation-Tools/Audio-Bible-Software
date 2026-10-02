package org.bibletranslationtools.shared.di.koin

import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.AndroidDatabaseDriverFactory
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.DatabaseDriverFactory
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightDatabaseProvider
import org.bibletranslationtools.otter.common.device.AndroidAudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AndroidAudioHardwareProvider
import org.bibletranslationtools.otter.common.device.AndroidAudioSink
import org.bibletranslationtools.otter.common.device.AndroidAudioSource
import org.bibletranslationtools.otter.common.device.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.AudioSink
import org.bibletranslationtools.otter.common.device.AudioSource
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

// Generic (given an IDirectoryProvider + the android Context, which the app's startKoin
// supplies via androidContext()).
val appDatabaseModule = module {
    single<DatabaseDriverFactory> { AndroidDatabaseDriverFactory(androidContext()) }
    single<DaoProvider> {
        SqlDelightDatabaseProvider(driverFactory = get(), directoryProvider = get()).provide()
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
