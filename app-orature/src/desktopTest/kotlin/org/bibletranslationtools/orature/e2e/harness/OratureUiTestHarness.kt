package org.bibletranslationtools.orature.e2e.harness

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bibletranslationtools.orature.di.oratureViewModelModule
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.device.AudioDevice
import org.bibletranslationtools.otter.common.device.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.AudioSink
import org.bibletranslationtools.otter.common.device.AudioSource
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.bibletranslationtools.otter.common.device.AudioSystemConfig
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.bibletranslationtools.shared.di.koin.sharedDesktopModules
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Boots Orature's real Koin graph for desktop Compose UI tests, with two substitutions:
 * app data lives in a fresh temp directory, and the microphone and speakers are fakes, so a
 * run needs no audio hardware and never touches the developer's own Orature projects.
 *
 * Mirrors the recorder's RecorderUiTestHarness. The fakes are copied rather than shared
 * because the two apps' test source sets cannot see each other.
 */
object OratureUiTestHarness {

    private var rootDir: File? = null

    fun start(): File {
        // Software rendering so headless CI machines can draw (and screenshot) the UI.
        System.setProperty("skiko.renderApi", "SOFTWARE")
        System.setProperty("java.awt.headless", "false")
        // Compose UI-test pointer events do not always arrive on Dispatchers.Main, and
        // Navigation's LifecycleRegistry throws "setCurrentState must be called on the main
        // thread" when they don't.
        disableLifecycleMainThreadCheck()
        stop()
        val root = File(System.getProperty("java.io.tmpdir"), "orature-e2e-${UUID.randomUUID()}")
        root.mkdirs()
        rootDir = root

        val source = FakeMicrophone()
        val sink = FakeSpeakers()
        val selector = FakeAudioDeviceSelector()

        startKoin {
            modules(
                sharedCommonModules + sharedDesktopModules + oratureViewModelModule + module {
                    single<IDirectoryProvider> {
                        DesktopDirectoryProvider(
                            appName = "Orature2",
                            pathSeparator = File.separator,
                            userHome = root.absolutePath,
                            windowsAppData = root.resolve("AppData").absolutePath.also {
                                File(it).mkdirs()
                            },
                            osName = "WINDOWS"
                        )
                    }
                    single<AudioSource> { source }
                    single<AudioSink> { sink }
                    single<AudioDeviceSelector> { selector }
                    single<AudioHardwareProvider> {
                        object : AudioHardwareProvider {
                            override fun createSink(device: AudioDevice): AudioSink = sink
                            override fun createSource(device: AudioDevice): AudioSource = source
                        }
                    }
                }
            )
        }

        val koin = GlobalContext.get()
        val config = koin.get<AudioSystemConfig>()
        val spec = AudioSpec()
        config.start()
        selector.getOutputDevices(spec).firstOrNull()?.let(selector::selectOutputDevice)
        selector.getInputDevices(spec).firstOrNull()?.let(selector::selectInputDevice)

        // The desktop app runs this in its splash window before the main window opens
        // (main.kt), and the main window starts straight at Home. Do the same here.
        koin.get<InitializeApp>().initApp().blockingSubscribe()

        return root
    }

    fun stop() {
        runCatching { stopKoin() }
        rootDir?.deleteRecursively()
        rootDir = null
    }

    private fun disableLifecycleMainThreadCheck() {
        runCatching {
            val field = Class.forName("androidx.lifecycle.MainDispatcherChecker")
                .getDeclaredField("isMainDispatcherAvailable")
            field.isAccessible = true
            field.setBoolean(null, false)
        }
    }
}

/**
 * A microphone that "hears" a quiet tone. Paced to real time: an unpaced fake fills the
 * recorder's buffer as fast as the CPU allows, which writes gigabytes in seconds.
 */
internal class FakeMicrophone : AudioSource {
    private var started = false

    override fun open(spec: AudioSpec) {}
    override fun start() { started = true }
    override fun stop() { started = false }
    override fun close() { started = false }

    override fun read(data: ByteArray, offset: Int, size: Int): Int {
        if (!started) return 0
        // 44.1 kHz, 16-bit mono = 88,200 bytes per second.
        Thread.sleep((size * 1000L / 88_200).coerceAtLeast(1))
        for (i in 0 until size) data[offset + i] = ((i % 64) - 32).toByte()
        return size
    }
}

/** Speakers that accept audio and advance their position as if it had been played. */
internal class FakeSpeakers : AudioSink {
    override var framePosition: Long = 0
    override var isRunning = false

    override fun open(spec: AudioSpec) {}
    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        framePosition += (size / 2).toLong()
        return size
    }
    override fun drain() {}
    override fun flush() {}
    override fun close() { isRunning = false }
    override fun start() { isRunning = true }
    override fun stop() { isRunning = false }
}

internal class FakeAudioDeviceSelector : AudioDeviceSelector {
    private val input = AudioDevice(id = "fake-in", name = "Fake Microphone", type = AudioDevice.DeviceType.INPUT)
    private val output = AudioDevice(id = "fake-out", name = "Fake Speakers", type = AudioDevice.DeviceType.OUTPUT)
    private val activeOut = MutableStateFlow<AudioDevice?>(output)
    private val activeIn = MutableStateFlow<AudioDevice?>(input)

    override val activeOutputDevice: Flow<AudioDevice?> = activeOut.asStateFlow()
    override val activeInputDevice: Flow<AudioDevice?> = activeIn.asStateFlow()

    override fun getOutputDevices(spec: AudioSpec): List<AudioDevice> = listOf(output)
    override fun getInputDevices(spec: AudioSpec): List<AudioDevice> = listOf(input)
    override fun selectOutputDevice(device: AudioDevice?) { activeOut.value = device }
    override fun selectInputDevice(device: AudioDevice?) { activeIn.value = device }
}
