package org.bibletranslationtools.recorder2.e2e.harness

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.device.newaudio.AudioDevice
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.newaudio.AudioSink
import org.bibletranslationtools.otter.common.device.newaudio.AudioSource
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.koin.core.context.GlobalContext
import org.koin.dsl.module

/**
 * Android instrumented e2e helpers: mock audio Koin module (used by [org.bibletranslationtools.recorder2.e2e.RecorderTestApplication])
 * and Genesis project seeding (parity with desktop [org.bibletranslationtools.bttrecorder2.e2e.harness.RecorderUiTestHarness]).
 */
object RecorderAndroidUiTestHarness {

    private val mockSource = MockAudioSource()
    private val mockSink = MockAudioSink()
    private val mockSelector = MockAudioDeviceSelector()

    val mockAudioModule = module {
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

    /**
     * Creates an Afar Genesis narration project from the seeded English ULB source and marks it
     * active so MainMenu Record opens the recorder directly.
     */
    fun seedGenesisProject() {
        val koin = GlobalContext.get()
        val createProject =
            koin.get<org.bibletranslationtools.otter.common.domain.collections.CreateProject>()
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
        // Translation row may already exist from a prior instrumented run on the same device.
        runCatching { createTranslation.create(sourceLang, target).blockingGet() }
        runBlocking {
            appPreferences.setActiveWorkbook(gen.id, targetBook.id)
            // Prefer a concrete chapter so MainMenu / recorder resolve a real target quickly.
            appPreferences.setActiveChapter(1)
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
