package org.bibletranslationtools.bttrecorder2.e2e.harness

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bibletranslationtools.bttrecorder2.di.koin.recorderViewModelModule
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.device.newaudio.AudioDevice
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.newaudio.AudioSink
import org.bibletranslationtools.otter.common.device.newaudio.AudioSource
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.device.newaudio.AudioSystemConfig
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.bibletranslationtools.shared.di.koin.sharedDesktopModules
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.util.UUID

/**
 * Boots Koin for desktop Compose UI e2e tests: isolated temp app data dirs and mocked audio
 * hardware so CI / headless runs do not need a microphone or speakers.
 */
object RecorderUiTestHarness {

    private var rootDir: File? = null

    fun start(): File {
        // Prefer software rendering for headless / CI Compose UI tests.
        System.setProperty("skiko.renderApi", "SOFTWARE")
        System.setProperty("java.awt.headless", "false")
        // Desktop Compose UI-test pointer events are not always on Dispatchers.Main;
        // Navigation's LifecycleRegistry otherwise throws
        // "setCurrentState must be called on the main thread".
        disableLifecycleMainThreadCheck()
        stop()
        val root = File(System.getProperty("java.io.tmpdir"), "recorder-e2e-${UUID.randomUUID()}")
        root.mkdirs()
        rootDir = root

        val mockSource = MockAudioSource()
        val mockSink = MockAudioSink()
        val mockSelector = MockAudioDeviceSelector()

        startKoin {
            modules(
                sharedCommonModules + sharedDesktopModules + recorderViewModelModule + module {
                    single<IDirectoryProvider> {
                        DesktopDirectoryProvider(
                            appName = "BTT Recorder",
                            pathSeparator = File.separator,
                            userHome = root.absolutePath,
                            windowsAppData = root.resolve("AppData").absolutePath.also {
                                File(it).mkdirs()
                            },
                            osName = "WINDOWS"
                        )
                    }
                    single<AudioSource> { mockSource }
                    single<AudioSink> { mockSink }
                    single<AudioDeviceSelector> { mockSelector }
                    single<AudioHardwareProvider> {
                        object : AudioHardwareProvider {
                            override fun createSink(device: AudioDevice): AudioSink = mockSink
                            override fun createSource(device: AudioDevice): AudioSource = mockSource
                        }
                    }
                }
            )
        }

        val koin = GlobalContext.get()
        val config = koin.get<AudioSystemConfig>()
        val selector = koin.get<AudioDeviceSelector>()
        val spec = AudioSpec()
        config.start()
        selector.getOutputDevices(spec).firstOrNull()?.let(selector::selectOutputDevice)
        selector.getInputDevices(spec).firstOrNull()?.let(selector::selectInputDevice)

        // Splash will also call this; priming here makes wizard/record flows ready faster when
        // tests skip waiting on the splash progress UI.
        koin.get<InitializeApp>().initApp().blockingSubscribe()

        return root
    }

    /**
     * Creates an Afar Genesis narration project from the seeded English ULB source and marks
     * it active so MainMenu Record opens the recorder directly.
     *
     * Used by [org.bibletranslationtools.bttrecorder2.e2e.RecorderRecordPlaybackE2ETest]
     * (parity with Android seeded record flow). Full UI create is covered by
     * [org.bibletranslationtools.bttrecorder2.e2e.RecorderWizardE2ETest].
     */
    fun seedGenesisProject() {
        val koin = GlobalContext.get()
        val createProject = koin.get<org.bibletranslationtools.otter.common.domain.collections.CreateProject>()
        val createTranslation =
            koin.get<org.bibletranslationtools.otter.common.domain.collections.CreateTranslation>()
        val collectionRepository =
            koin.get<org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository>()
        val languageRepository =
            koin.get<org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository>()
        val resourceMetadataRepository =
            koin.get<org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository>()
        val appPreferences = koin.get<org.bibletranslationtools.shared.preferences.IAppPreferences>()

        val target = languageRepository.getAll().blockingGet().first { it.slug == "aa" }
        val sourceMeta = resourceMetadataRepository.getAllSources().blockingGet()
            .first { it.language.slug == "en" }
        val sourceLang = sourceMeta.language
        val root = collectionRepository.getRootSources().blockingGet()
            .first { it.resourceContainer?.id == sourceMeta.id }
        val gen = collectionRepository.getChildren(root).blockingGet().first { it.slug == "gen" }
        val targetBook = createProject.create(
            sourceProject = gen,
            targetLanguage = target,
            mode = org.bibletranslationtools.otter.common.data.primitives.ProjectMode.NARRATION,
            deriveProjectFromVerses = true
        ).blockingGet()
        // Single-book create does not insert a Translation row; workbook open needs it.
        createTranslation.create(sourceLang, target).blockingGet()
        kotlinx.coroutines.runBlocking {
            appPreferences.setActiveWorkbook(gen.id, targetBook.id)
        }
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

internal class MockAudioSource : AudioSource {
    var isOpen = false
    var isStarted = false

    override fun open(spec: AudioSpec) {
        isOpen = true
    }

    override fun start() {
        isStarted = true
    }

    override fun stop() {
        isStarted = false
    }

    override fun close() {
        isOpen = false
    }

    override fun read(data: ByteArray, offset: Int, size: Int): Int {
        if (!isStarted) return 0
        for (i in 0 until size) data[offset + i] = (i % 128).toByte()
        return size
    }
}

internal class MockAudioSink : AudioSink {
    var isOpen = false
    var isStarted = false
    var bytesWritten = 0
    override var framePosition: Long = 0
    override var isRunning = false

    override fun open(spec: AudioSpec) {
        isOpen = true
    }

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        bytesWritten += size
        framePosition += (size / 2).toLong()
        return size
    }

    override fun drain() {}
    override fun flush() {}
    override fun close() {
        isOpen = false
    }

    override fun start() {
        isStarted = true
        isRunning = true
    }

    override fun stop() {
        isStarted = false
        isRunning = false
    }
}

internal class MockAudioDeviceSelector : AudioDeviceSelector {
    private val input = AudioDevice(id = "mock-in", name = "Mock Input", type = AudioDevice.DeviceType.INPUT)
    private val output = AudioDevice(id = "mock-out", name = "Mock Output", type = AudioDevice.DeviceType.OUTPUT)
    private val _activeOut = MutableStateFlow<AudioDevice?>(output)
    private val _activeIn = MutableStateFlow<AudioDevice?>(input)

    override val activeOutputDevice: Flow<AudioDevice?> = _activeOut.asStateFlow()
    override val activeInputDevice: Flow<AudioDevice?> = _activeIn.asStateFlow()

    override fun getOutputDevices(spec: AudioSpec): List<AudioDevice> = listOf(output)
    override fun getInputDevices(spec: AudioSpec): List<AudioDevice> = listOf(input)
    override fun selectOutputDevice(device: AudioDevice?) {
        _activeOut.value = device
    }

    override fun selectInputDevice(device: AudioDevice?) {
        _activeIn.value = device
    }
}
