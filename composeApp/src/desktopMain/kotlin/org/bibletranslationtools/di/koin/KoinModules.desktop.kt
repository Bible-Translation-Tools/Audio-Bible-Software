package org.bibletranslationtools.di.koin

import org.bibletranslationtools.bttrecorder2.di.koin.commonModules
import org.bibletranslationtools.otter.common.api.persistence.IAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.database.AppDatabase
import org.koin.dsl.module
import java.io.File

val directoryProviderModule = module {
    single<IDirectoryProvider> {
        DesktopDirectoryProvider(
            appName = "BTT Recorder",
            pathSeparator = System.getProperty("file.separator"),
            userHome = System.getProperty("user.home"),
            windowsAppData = System.getenv("APPDATA"),
            osName = System.getProperty("os.name").uppercase()
        )
    }
}

val appDatabaseModule = module {
    single<IAppDatabase> {
        val directoryProvider = get<IDirectoryProvider>()
        AppDatabase(
            directoryProvider.databaseDirectory.resolve(File("tr.sqlite")),
            directoryProvider
        )
    }
}

val appModules = listOf(
    appDatabaseModule,
    directoryProviderModule,
    *commonModules.toTypedArray()
)