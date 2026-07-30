package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.shared.preferences.AppSettings
import org.bibletranslationtools.shared.preferences.AppSettings.Companion.DEFAULT_LANG_NAMES_URL
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.shared.preferences.ThemeMode
import org.bibletranslationtools.otter.common.device.AudioDevice
import org.bibletranslationtools.otter.common.device.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.bibletranslationtools.otter.common.domain.languages.ImportLanguages
import org.koin.core.component.KoinComponent
import java.util.Locale

/**
 * One selectable UI language. [tag] is a BCP-47 tag; a null tag means "follow the
 * system locale". Only languages with bundled string resources should appear.
 */
data class OratureLanguageOption(val tag: String?, val displayName: String)

/** Progress/result of a langnames-URL "check for updates" fetch. */
sealed interface OratureLangNamesUpdateState {
    data object Idle : OratureLangNamesUpdateState
    data object InProgress : OratureLangNamesUpdateState
    data object Success : OratureLangNamesUpdateState
    data class Error(val message: String) : OratureLangNamesUpdateState
}

data class OratureSettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val outputDevices: List<AudioDevice> = emptyList(),
    val inputDevices: List<AudioDevice> = emptyList(),
    val selectedOutputId: String? = null,
    val selectedInputId: String? = null,
    val appLanguageTag: String? = null,
    val languageOptions: List<OratureLanguageOption> = emptyList(),
    val langNamesUrl: String = AppSettings.DEFAULT_LANG_NAMES_URL,
    val langNamesUpdateState: OratureLangNamesUpdateState = OratureLangNamesUpdateState.Idle
)

/**
 * Orature's own settings ViewModel. It drives the settings drawer (theme, UI language,
 * output/input audio device, langnames-URL updater) and persists every choice through
 * the SHARED backend: [IAppPreferences] for the durable preference store,
 * [AudioDeviceSelector] for the live device selection, and [ImportLanguages] for the
 * langnames fetch. This is the same data path the recorder's SettingsViewModel uses —
 * deliberately, since both apps target the same shared backend — but it lives in
 * Orature's package.
 */
class OratureSettingsViewModel(
    private val appPreferences: IAppPreferences,
    private val deviceSelector: AudioDeviceSelector,
    private val importLanguages: ImportLanguages
) : ViewModel(), KoinComponent {

    // Koin no-arg constructor for production; the injected constructor is used by tests.
    constructor() : this(
        appPreferences = koinGet(),
        deviceSelector = koinGet(),
        importLanguages = koinGet()
    )

    private val _uiState = MutableStateFlow(
        OratureSettingsUiState(
            languageOptions = DEFAULT_LANGUAGE_OPTIONS
        )
    )
    val uiState: StateFlow<OratureSettingsUiState> = _uiState.asStateFlow()

    init {
        loadDevices()
        launchLogged {
            appPreferences.appSettings.collect { settings: AppSettings ->
                _uiState.update {
                    it.copy(
                        themeMode = settings.themeMode,
                        selectedOutputId = settings.outputDeviceId,
                        selectedInputId = settings.inputDeviceId,
                        appLanguageTag = settings.appLanguageTag,
                        langNamesUrl = settings.langNamesUrl
                    )
                }
            }
        }
    }

    /** (Re)reads the currently-available hardware devices. Safe to call on drawer open. */
    fun loadDevices() {
        launchLogged(Dispatchers.IO) {
            val spec = AudioSpec()
            val out = runCatching { deviceSelector.getOutputDevices(spec) }.getOrDefault(emptyList())
            val input = runCatching { deviceSelector.getInputDevices(spec) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(outputDevices = out, inputDevices = input) }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        launchLogged { appPreferences.setThemeMode(mode) }
    }

    fun selectOutputDevice(device: AudioDevice) {
        deviceSelector.selectOutputDevice(device)
        launchLogged { appPreferences.setOutputDeviceId(device.id) }
    }

    fun selectInputDevice(device: AudioDevice) {
        deviceSelector.selectInputDevice(device)
        launchLogged { appPreferences.setInputDeviceId(device.id) }
    }

    fun setAppLanguage(tag: String?) {
        // Apply the JVM default locale SYNCHRONOUSLY first, before the persisted-settings flow emits
        // and the UI re-keys on the new language (OratureTheme). Otherwise the recomposition can read
        // the old Locale.current and Compose resources would resolve to the previous language.
        applyLocale(tag)
        launchLogged { appPreferences.setAppLanguageTag(tag) }
    }

    private fun applyLocale(tag: String?) {
        runCatching {
            val locale = if (tag.isNullOrBlank()) Locale.getDefault() else Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
        }
    }

    fun setLangNamesUrl(url: String) {
        launchLogged { appPreferences.setLangNamesUrl(url) }
    }

    /** Restores the langnames URL to the shared default. */
    fun resetLangNamesUrl() {
        setLangNamesUrl(DEFAULT_LANG_NAMES_URL)
    }

    /** Fetches langnames from the configured URL, exposing progress + result. */
    fun updateLanguageNames() {
        if (_uiState.value.langNamesUpdateState is OratureLangNamesUpdateState.InProgress) return
        val url = _uiState.value.langNamesUrl.ifBlank { DEFAULT_LANG_NAMES_URL }
        launchLogged(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(langNamesUpdateState = OratureLangNamesUpdateState.InProgress) }
            }
            val result = runCatching { importLanguages.update(url).await() }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        langNamesUpdateState = if (result.isSuccess) {
                            OratureLangNamesUpdateState.Success
                        } else {
                            OratureLangNamesUpdateState.Error(
                                result.exceptionOrNull()?.message ?: "Unknown error"
                            )
                        }
                    )
                }
            }
        }
    }

    fun dismissLangNamesResult() {
        _uiState.update { it.copy(langNamesUpdateState = OratureLangNamesUpdateState.Idle) }
    }

    companion object {
        // The selectable UI languages = every locale we ship string resources for
        // (composeResources/values-<lang>). Shown in each language's own autonym so users
        // recognize their language. "System default" (null tag) is always offered first and is
        // localized in the drawer. Keep in sync with the values-<lang> folders.
        val DEFAULT_LANGUAGE_OPTIONS = listOf(
            OratureLanguageOption(tag = null, displayName = "System default"),
            OratureLanguageOption(tag = "en", displayName = "English"),
            OratureLanguageOption(tag = "ar", displayName = "العربية"),
            OratureLanguageOption(tag = "es", displayName = "Español"),
            OratureLanguageOption(tag = "fr", displayName = "Français"),
            OratureLanguageOption(tag = "id", displayName = "Bahasa Indonesia"),
            OratureLanguageOption(tag = "my", displayName = "မြန်မာ"),
            OratureLanguageOption(tag = "pt", displayName = "Português"),
            OratureLanguageOption(tag = "ru", displayName = "Русский"),
            OratureLanguageOption(tag = "sw", displayName = "Kiswahili"),
            OratureLanguageOption(tag = "te", displayName = "తెలుగు"),
            OratureLanguageOption(tag = "vi", displayName = "Tiếng Việt"),
            OratureLanguageOption(tag = "zh", displayName = "中文")
        )

        private inline fun <reified T : Any> koinGet(): T =
            org.koin.mp.KoinPlatform.getKoin().get()
    }
}
