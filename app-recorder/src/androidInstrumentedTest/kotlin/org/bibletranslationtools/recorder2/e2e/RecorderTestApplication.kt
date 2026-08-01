package org.bibletranslationtools.recorder2.e2e

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import org.bibletranslationtools.bttrecorder2.di.koin.recorderViewModelModule
import org.bibletranslationtools.di.koin.androidContextModule
import org.bibletranslationtools.di.koin.directoryProviderModule
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.device.newaudio.AudioSystemConfig
import org.bibletranslationtools.recorder2.e2e.harness.RecorderAndroidUiTestHarness
import org.bibletranslationtools.shared.di.koin.appDatabaseModule
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

/**
 * Production-like Koin graph for Android e2e with mock audio (no mic/speakers). App init stays on
 * the splash path so [Application.onCreate] does not block the main thread with [org.bibletranslationtools.otter.common.initialization.InitializeApp].
 */
class RecorderTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Compose UI-test frame deferral can resume coroutines off the Android main looper.
        // Lifecycle/NavController then throw; treat all threads as main for e2e only (desktop
        // harness disables MainDispatcherChecker for the same reason).
        relaxLifecycleMainThreadEnforcement()

        startKoin {
            androidLogger()
            androidContext(this@RecorderTestApplication)
            modules(
                sharedCommonModules +
                    listOf(appDatabaseModule) +
                    androidContextModule +
                    directoryProviderModule +
                    recorderViewModelModule +
                    RecorderAndroidUiTestHarness.mockAudioModule
            )
        }

        val koin = GlobalContext.get()
        val config = koin.get<AudioSystemConfig>()
        val selector = koin.get<AudioDeviceSelector>()
        val spec = AudioSpec()
        config.start()
        selector.getOutputDevices(spec).firstOrNull()?.let(selector::selectOutputDevice)
        selector.getInputDevices(spec).firstOrNull()?.let(selector::selectInputDevice)
    }

    private fun relaxLifecycleMainThreadEnforcement() {
        runCatching {
            val field = Class.forName("androidx.lifecycle.MainDispatcherChecker")
                .getDeclaredField("isMainDispatcherAvailable")
            field.isAccessible = true
            field.setBoolean(null, false)
        }
        runCatching {
            val ioExecutor = java.util.concurrent.Executors.newCachedThreadPool()
            ArchTaskExecutor.getInstance().setDelegate(
                object : TaskExecutor() {
                    override fun executeOnDiskIO(runnable: Runnable) {
                        ioExecutor.execute(runnable)
                    }

                    override fun postToMainThread(runnable: Runnable) {
                        Handler(Looper.getMainLooper()).post(runnable)
                    }

                    override fun isMainThread(): Boolean = true
                }
            )
        }
    }
}
