package org.bibletranslationtools.di.koin

import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.koin.dsl.module

// App-specific: the appName determines the desktop data directory, so each app supplies
// its own. (Orature supplies its own with appName "Orature".) The DB + audio modules are
// shared — see :shared sharedDesktopModules.
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
