package org.bibletranslationtools.orature.di

import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.koin.dsl.module

// Orature's own data directory (appName "Orature2") — separate from the recorder's.
val oratureDirectoryProviderModule = module {
    single<IDirectoryProvider> {
        DesktopDirectoryProvider(
            appName = "Orature2",
            pathSeparator = System.getProperty("file.separator"),
            userHome = System.getProperty("user.home"),
            windowsAppData = System.getenv("APPDATA"),
            osName = System.getProperty("os.name").uppercase()
        )
    }
}
