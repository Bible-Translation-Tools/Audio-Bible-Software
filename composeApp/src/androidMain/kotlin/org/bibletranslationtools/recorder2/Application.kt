package org.bibletranslationtools.recorder2

import android.app.Application
import org.bibletranslationtools.di.koin.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class Application: Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@Application)
            modules(appModules)
        }
    }
}