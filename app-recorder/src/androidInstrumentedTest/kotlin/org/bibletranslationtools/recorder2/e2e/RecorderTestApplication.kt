package org.bibletranslationtools.recorder2.e2e

import android.app.Application
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
 * the splash path so [Application.onCreate] does not block the main thread with
 * [org.bibletranslationtools.otter.common.initialization.InitializeApp].
 */
class RecorderTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()

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
}
