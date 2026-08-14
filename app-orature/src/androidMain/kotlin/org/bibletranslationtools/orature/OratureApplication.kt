package org.bibletranslationtools.orature

import android.app.Application
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins
import org.bibletranslationtools.orature.crash.GithubCrashReportUploader
import org.bibletranslationtools.orature.crash.OratureCrashReporter
import org.bibletranslationtools.orature.crash.SentryCrashReporter
import org.bibletranslationtools.orature.di.oratureDirectoryProviderModule
import org.bibletranslationtools.orature.di.oratureViewModelModule
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.shared.di.koin.sharedAndroidModules
import org.bibletranslationtools.shared.logging.logFailure
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import java.io.File
import kotlin.system.exitProcess

class OratureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidLogger()
            androidContext(this@OratureApplication)
            modules(
                sharedCommonModules + sharedAndroidModules + oratureDirectoryProviderModule +
                    oratureViewModelModule
            )
        }.koin

        // RxJava routes any error arriving after its subscriber is gone to the global handler,
        // and the default handler rethrows on the worker thread — which kills the process. That is
        // how a disposed init subscription turned into a fatal
        // `UndeliverableException: RuntimeException: InterruptedException` rather than a log line.
        // Losing an error nobody can consume is not worth a crash, so log it and carry on. Errors
        // that still HAVE a subscriber are unaffected and reach it as normal.
        RxJavaPlugins.setErrorHandler { e ->
            val cause = (e as? UndeliverableException)?.cause ?: e
            logFailure(this, "an RxJava error with no remaining subscriber", cause)
        }

        // Global crash handler (JVM: OtterExceptionHandler). No separate window on Android, so the
        // root composable shows the crash screen; close = quit the process.
        val directoryProvider = koin.get<IAppDirectories>()
        OratureCrashReporter.install(
            uploaders = listOfNotNull(
                GithubCrashReportUploader.fromClasspath(),
                SentryCrashReporter.fromClasspath()
            ),
            logProvider = {
                runCatching { File(directoryProvider.logsDirectory, "orature.log").readText() }.getOrNull()
            },
            closeApp = { exitProcess(1) }
        )
    }
}
