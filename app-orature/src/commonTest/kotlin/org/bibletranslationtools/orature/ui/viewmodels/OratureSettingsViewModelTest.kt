package org.bibletranslationtools.orature.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.reactivex.Completable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.otter.common.device.newaudio.AudioDevice
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.domain.languages.ImportLanguages
import org.bibletranslationtools.shared.preferences.AppSettings
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.shared.preferences.ThemeMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OratureSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var appPreferences: IAppPreferences
    private lateinit var deviceSelector: AudioDeviceSelector
    private lateinit var importLanguages: ImportLanguages
    private lateinit var settingsFlow: MutableStateFlow<AppSettings>

    private val outA = AudioDevice("out-a", "Output A", AudioDevice.DeviceType.OUTPUT)
    private val outB = AudioDevice("out-b", "Output B", AudioDevice.DeviceType.OUTPUT)
    private val inA = AudioDevice("in-a", "Mic A", AudioDevice.DeviceType.INPUT)

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsFlow = MutableStateFlow(AppSettings())

        appPreferences = mockk(relaxed = true)
        every { appPreferences.appSettings } returns settingsFlow

        deviceSelector = mockk(relaxed = true)
        every { deviceSelector.getOutputDevices(any<AudioSpec>()) } returns listOf(outA, outB)
        every { deviceSelector.getInputDevices(any<AudioSpec>()) } returns listOf(inA)

        importLanguages = mockk(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() =
        OratureSettingsViewModel(appPreferences, deviceSelector, importLanguages)

    @Test
    fun `output and input device lists map from the device selector`() {
        // loadDevices() runs on Dispatchers.IO then republishes on Dispatchers.Main. Use
        // a real main dispatcher (not the virtual test scheduler) so both actually run,
        // and await the mapped state with real wall-clock time.
        Dispatchers.resetMain()
        try {
            val vm = createViewModel()
            val state = runBlocking {
                withTimeout(5_000) {
                    vm.uiState.first { it.outputDevices.isNotEmpty() && it.inputDevices.isNotEmpty() }
                }
            }
            assertEquals(listOf(outA, outB), state.outputDevices)
            assertEquals(listOf(inA), state.inputDevices)
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `selecting an output device selects it live and persists its id`() = runTest(testDispatcher) {
        coEvery { appPreferences.setOutputDeviceId(any()) } returns Unit
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectOutputDevice(outB)
        testDispatcher.scheduler.advanceUntilIdle()

        verify { deviceSelector.selectOutputDevice(outB) }
        coVerify { appPreferences.setOutputDeviceId("out-b") }
    }

    @Test
    fun `selecting an input device selects it live and persists its id`() = runTest(testDispatcher) {
        coEvery { appPreferences.setInputDeviceId(any()) } returns Unit
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectInputDevice(inA)
        testDispatcher.scheduler.advanceUntilIdle()

        verify { deviceSelector.selectInputDevice(inA) }
        coVerify { appPreferences.setInputDeviceId("in-a") }
    }

    @Test
    fun `setting the theme mode persists it`() = runTest(testDispatcher) {
        val modeSlot = slot<ThemeMode>()
        coEvery { appPreferences.setThemeMode(capture(modeSlot)) } returns Unit
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setThemeMode(ThemeMode.DARK)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { appPreferences.setThemeMode(ThemeMode.DARK) }
        assertEquals(ThemeMode.DARK, modeSlot.captured)
    }

    @Test
    fun `theme mode from preferences flows into ui state`() = runTest(testDispatcher) {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, vm.uiState.value.themeMode)

        settingsFlow.value = AppSettings(themeMode = ThemeMode.LIGHT)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, vm.uiState.value.themeMode)
    }

    @Test
    fun `setting the app language persists the tag`() = runTest(testDispatcher) {
        val tagSlot = slot<String?>()
        coEvery { appPreferences.setAppLanguageTag(captureNullable(tagSlot)) } returns Unit
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAppLanguage("en")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { appPreferences.setAppLanguageTag("en") }
        assertEquals("en", tagSlot.captured)
    }

    @Test
    fun `default language options include system default and English`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val tags = vm.uiState.value.languageOptions.map { it.tag }
        assertEquals(listOf(null, "en"), tags)
    }

    @Test
    fun `setting the langnames url persists it`() = runTest(testDispatcher) {
        val urlSlot = slot<String>()
        coEvery { appPreferences.setLangNamesUrl(capture(urlSlot)) } returns Unit
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setLangNamesUrl("https://example.org/langnames.json")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { appPreferences.setLangNamesUrl("https://example.org/langnames.json") }
        assertEquals("https://example.org/langnames.json", urlSlot.captured)
    }

    @Test
    fun `reset langnames url persists the shared default`() = runTest(testDispatcher) {
        coEvery { appPreferences.setLangNamesUrl(any()) } returns Unit
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.resetLangNamesUrl()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { appPreferences.setLangNamesUrl(AppSettings.DEFAULT_LANG_NAMES_URL) }
    }

    @Test
    fun `starting empty means langnames state is Idle`() = runTest(testDispatcher) {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.langNamesUpdateState is OratureLangNamesUpdateState.Idle)
    }

    @Test
    fun `updateLanguageNames reaches Success when the fetch completes`() = runReal {
        every { importLanguages.update(any()) } returns Completable.complete()

        val vm = createViewModel()
        vm.uiState.first { it.outputDevices.isNotEmpty() } // ensure init settled

        vm.updateLanguageNames()
        val state = vm.uiState.first { it.langNamesUpdateState is OratureLangNamesUpdateState.Success }

        assertTrue(state.langNamesUpdateState is OratureLangNamesUpdateState.Success)
        verify { importLanguages.update(any()) }
    }

    @Test
    fun `updateLanguageNames reaches Error with the message when the fetch fails`() = runReal {
        every { importLanguages.update(any()) } returns
            Completable.error(RuntimeException("boom"))

        val vm = createViewModel()
        vm.uiState.first { it.outputDevices.isNotEmpty() }

        vm.updateLanguageNames()
        val state = vm.uiState.first { it.langNamesUpdateState is OratureLangNamesUpdateState.Error }

        val error = state.langNamesUpdateState as OratureLangNamesUpdateState.Error
        assertEquals("boom", error.message)
    }

    @Test
    fun `dismissLangNamesResult returns state to Idle`() = runReal {
        every { importLanguages.update(any()) } returns Completable.complete()
        val vm = createViewModel()
        vm.uiState.first { it.outputDevices.isNotEmpty() }

        vm.updateLanguageNames()
        vm.uiState.first { it.langNamesUpdateState is OratureLangNamesUpdateState.Success }

        vm.dismissLangNamesResult()
        assertTrue(vm.uiState.value.langNamesUpdateState is OratureLangNamesUpdateState.Idle)
    }

    /**
     * Runs a block against REAL dispatchers (IO + Main). updateLanguageNames() and
     * loadDevices() hop through Dispatchers.IO then republish on Dispatchers.Main, so the
     * virtual test scheduler can't drive them; this uses wall-clock time with a timeout.
     */
    private fun runReal(block: suspend () -> Unit) {
        Dispatchers.resetMain()
        try {
            runBlocking { withTimeout(5_000) { block() } }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }
}
