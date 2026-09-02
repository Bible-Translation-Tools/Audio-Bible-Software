package org.bibletranslationtools.shared.di.koin

import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.AndroidDatabaseDriverFactory
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.DATABASE_FILE_NAME
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.DatabaseDriverFactory
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightDatabaseProvider
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
    // The jOOQ backend. Unused by any repository now that DaoProvider (below) is the seam they
    // resolve; kept in the graph (and AndroidAppDatabase/SQLDroid/xerial/sqliteassethelper/
    // jniLibs/assets/databases kept on the classpath) only because the cold-init benchmark in
    // DatabaseInitBenchmark constructs AndroidAppDatabase directly to compare against SQLDelight.
    // Phase 6 removes all of it.
    single<IAppDatabase> {
        val directoryProvider = get<IDirectoryProvider>()
        AndroidAppDatabase(
            androidContext(),
            directoryProvider.databaseDirectory.resolve(File(DATABASE_FILE_NAME)),
            directoryProvider
        )
    }

    single<DatabaseDriverFactory> { AndroidDatabaseDriverFactory(androidContext()) }
    single<DaoProvider> {
        // Active backend: SQLDelight.
        SqlDelightDatabaseProvider(driverFactory = get(), directoryProvider = get()).provide()
        // To switch to jOOQ: comment the line above and uncomment (same caveat as desktop):
        // JooqDaoProvider(get<IAppDatabase>())
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
