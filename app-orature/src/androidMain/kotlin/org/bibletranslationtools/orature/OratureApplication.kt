package org.bibletranslationtools.orature

import android.app.Application
import org.bibletranslationtools.orature.di.oratureDirectoryProviderModule
import org.bibletranslationtools.orature.di.oratureViewModelModule
import org.bibletranslationtools.shared.di.koin.sharedAndroidModules
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class OratureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@OratureApplication)
            modules(
                sharedCommonModules + sharedAndroidModules + oratureDirectoryProviderModule +
                    oratureViewModelModule
            )
        }
    }
}
