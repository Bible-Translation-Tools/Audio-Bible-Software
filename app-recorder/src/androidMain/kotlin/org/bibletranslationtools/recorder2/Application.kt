package org.bibletranslationtools.recorder2

import android.app.Application
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins
import org.bibletranslationtools.bttrecorder2.di.koin.recorderMigrationModule
import org.bibletranslationtools.bttrecorder2.di.koin.recorderNarrationModule
import org.bibletranslationtools.bttrecorder2.di.koin.recorderViewModelModule
import org.bibletranslationtools.di.koin.androidContextModule
import org.bibletranslationtools.di.koin.directoryProviderModule
import org.bibletranslationtools.di.koin.legacyRecorderStoreModule
import org.bibletranslationtools.shared.di.koin.sharedAndroidModules
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.bibletranslationtools.shared.logging.logFailure
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class Application: Application() {
    override fun onCreate() {
        super.onCreate()

        // RxJava routes any error arriving after its subscriber is gone to the global handler, and
        // the default handler rethrows on the worker thread — which kills the process. Startup is
        // where that bites: the splash subscribes app initialization from a composition-scoped
        // coroutine, so anything that disposes it interrupts the initializer mid-step, and the
        // InterruptedException it then reports has nowhere to go. Losing an error nobody can consume
        // is not worth a crash. Errors that still have a subscriber are unaffected.
        //
        // The same handler, for the same reason, is installed by OratureApplication.
        RxJavaPlugins.setErrorHandler { e ->
            val cause = (e as? UndeliverableException)?.cause ?: e
            logFailure(this, "an RxJava error with no remaining subscriber", cause)
        }

        startKoin {
            androidLogger()
            androidContext(this@Application)
            modules(
                sharedCommonModules + sharedAndroidModules +
                androidContextModule + directoryProviderModule +
                recorderViewModelModule + recorderNarrationModule +
                legacyRecorderStoreModule + recorderMigrationModule
            )
        }
    }
}