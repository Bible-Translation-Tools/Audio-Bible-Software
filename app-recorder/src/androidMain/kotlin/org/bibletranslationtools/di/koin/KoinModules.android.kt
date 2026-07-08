package org.bibletranslationtools.di.koin

import android.content.Context
import org.bibletranslationtools.bttrecorder2.di.koin.AppContext
import org.bibletranslationtools.bttrecorder2.di.koin.commonModules
import org.bibletranslationtools.otter.common.api.persistence.IAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.AndroidDirectoryProvider
import org.bibletranslationtools.otter.database.AndroidAppDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

class AndroidAppContext(val context: Context) : AppContext

val androidContextModule = module {
    single<AppContext> { AndroidAppContext(androidContext()) }
}

val directoryProviderModule = module {
    single<IDirectoryProvider> { AndroidDirectoryProvider(get()) }
}

val appDatabaseModule = module {
    single<IAppDatabase> {
        val directoryProvider = get<IDirectoryProvider>()
        AndroidAppDatabase(
            androidContext(),
            directoryProvider.databaseDirectory.resolve(File("tr.sqlite")),
            directoryProvider
        )
    }
//    single<IBurritoLoader> { BurritoLoader() }
}

val appModules = listOf(
    androidContextModule,
    appDatabaseModule,
    directoryProviderModule,
    *commonModules.toTypedArray()
)