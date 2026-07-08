package org.bibletranslationtools.recorder2

import android.app.Application
import org.bibletranslationtools.bttrecorder2.di.koin.recorderViewModelModule
import org.bibletranslationtools.di.koin.androidContextModule
import org.bibletranslationtools.di.koin.directoryProviderModule
import org.bibletranslationtools.shared.di.koin.sharedAndroidModules
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class Application: Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@Application)
            modules(
                sharedCommonModules + sharedAndroidModules +
                    androidContextModule + directoryProviderModule + recorderViewModelModule
            )
        }
    }
}