package org.bibletranslationtools.orature

import android.app.Application
import org.bibletranslationtools.orature.crash.GithubCrashReportUploader
import org.bibletranslationtools.orature.crash.OratureCrashReporter
import org.bibletranslationtools.orature.crash.SentryCrashReporter
import org.bibletranslationtools.orature.di.oratureDirectoryProviderModule
import org.bibletranslationtools.orature.di.oratureViewModelModule
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.shared.di.koin.sharedAndroidModules
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
