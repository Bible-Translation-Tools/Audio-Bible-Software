package org.bibletranslationtools.di.koin

import android.content.Context
import org.bibletranslationtools.bttrecorder2.di.koin.AppContext
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.AndroidDirectoryProvider
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

class AndroidAppContext(val context: Context) : AppContext

val androidContextModule = module {
    single<AppContext> { AndroidAppContext(androidContext()) }
}

// App-specific directory provider (Context-backed). DB + audio are shared — see :shared
// sharedAndroidModules.
val directoryProviderModule = module {
    single<IDirectoryProvider> { AndroidDirectoryProvider(get()) }
}
