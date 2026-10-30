package org.bibletranslationtools.recorder2

import android.app.Application
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins
import org.bibletranslationtools.bttrecorder2.di.koin.recorderViewModelModule
import org.bibletranslationtools.di.koin.androidContextModule
import org.bibletranslationtools.di.koin.directoryProviderModule
import org.bibletranslationtools.shared.di.koin.sharedAndroidModules
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.bibletranslationtools.shared.logging.logFailure
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

        // An RxJava error arriving after its subscriber is gone and goes to the global
        // handler, whose default rethrows and kills the process. Log it instead.
        RxJavaPlugins.setErrorHandler { e ->
            val cause = (e as? UndeliverableException)?.cause ?: e
            logFailure(this, "an RxJava error with no remaining subscriber", cause)
        }
    }
}