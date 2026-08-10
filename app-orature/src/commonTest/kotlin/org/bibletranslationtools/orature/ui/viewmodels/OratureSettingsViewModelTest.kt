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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bibletranslationtools.otter.common.device.AudioDevice
import org.bibletranslationtools.otter.common.device.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AudioSpec
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
        OratureSettingsViewModel(appPreferences, deviceSelector, importLanguages, testDispatcher)

    @Test
    fun `output and input device lists map from the device selector`() = runVmTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(outA, outB), state.outputDevices)
        assertEquals(listOf(inA), state.inputDevices)
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
    fun `default language options offer system default plus every shipped locale`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val tags = vm.uiState.value.languageOptions.map { it.tag }
        // "System default" (null) first, then one entry per composeResources/values-<lang> bundle we
        // ship translations for. Keep in sync when a locale is added/removed.
        assertEquals(
            listOf(null, "en", "ar", "es", "fr", "id", "my", "pt", "ru", "sw", "te", "vi", "zh"),
            tags
        )
        // Each real language is labelled in its own autonym so users can find their language.
        val displayNames = vm.uiState.value.languageOptions.associate { it.tag to it.displayName }
        assertEquals("العربية", displayNames["ar"])
        assertEquals("Français", displayNames["fr"])
        assertEquals("中文", displayNames["zh"])
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
    fun `updateLanguageNames reaches Success when the fetch completes`() = runVmTest {
        every { importLanguages.update(any()) } returns Completable.complete()

        val vm = createViewModel()
        vm.uiState.first { it.outputDevices.isNotEmpty() } // ensure init settled

        vm.updateLanguageNames()
        val state = vm.uiState.first { it.langNamesUpdateState is OratureLangNamesUpdateState.Success }

        assertTrue(state.langNamesUpdateState is OratureLangNamesUpdateState.Success)
        verify { importLanguages.update(any()) }
    }

    @Test
    fun `updateLanguageNames reaches Error with the message when the fetch fails`() = runVmTest {
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
    fun `dismissLangNamesResult returns state to Idle`() = runVmTest {
        every { importLanguages.update(any()) } returns Completable.complete()
        val vm = createViewModel()
        vm.uiState.first { it.outputDevices.isNotEmpty() }

        vm.updateLanguageNames()
        vm.uiState.first { it.langNamesUpdateState is OratureLangNamesUpdateState.Success }

        vm.dismissLangNamesResult()
        assertTrue(vm.uiState.value.langNamesUpdateState is OratureLangNamesUpdateState.Idle)
    }

    /**
     * Runs the body on [testDispatcher], which is where the VM now sends its IO work.
     *
     * This replaces a `runReal` helper that dropped to real dispatchers and awaited state on a wall
     * clock, because `loadDevices()` and `updateLanguageNames()` hopped to a hard-coded
     * `Dispatchers.IO` that the virtual scheduler could not drive. The VM takes a dispatcher now,
     * so it can.
     */
    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(testDispatcher) { block() }
}
