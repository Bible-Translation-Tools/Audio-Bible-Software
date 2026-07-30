package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.shared.preferences.AppSettings
import org.bibletranslationtools.shared.preferences.AppSettings.Companion.DEFAULT_LANG_NAMES_URL
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.shared.preferences.ThemeMode
import org.bibletranslationtools.otter.common.device.newaudio.AudioDevice
import org.bibletranslationtools.otter.common.device.newaudio.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.domain.languages.ImportLanguages
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale

/**
 * One selectable app language. [tag] is a BCP-47 tag; a null tag means "follow
 * the system locale". Only languages with bundled string resources should appear.
 */
data class AppLanguageOption(val tag: String?, val displayName: String)

sealed interface LangNamesUpdateState {
    data object Idle : LangNamesUpdateState
    data object InProgress : LangNamesUpdateState
    data object Success : LangNamesUpdateState
    data class Error(val message: String) : LangNamesUpdateState
}

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val outputDevices: List<AudioDevice> = emptyList(),
    val inputDevices: List<AudioDevice> = emptyList(),
    val selectedOutputId: String? = null,
    val selectedInputId: String? = null,
    val appLanguageTag: String? = null,
    val languageOptions: List<AppLanguageOption> = emptyList(),
    val langNamesUrl: String = AppSettings.DEFAULT_LANG_NAMES_URL,
    val langNamesUpdateState: LangNamesUpdateState = LangNamesUpdateState.Idle
)

class SettingsViewModel : ViewModel(), KoinComponent {

    private val appPreferences: IAppPreferences by inject()
    private val deviceSelector: AudioDeviceSelector by inject()
    private val importLanguages: ImportLanguages by inject()


    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // The set of selectable languages is whatever we ship translations for.
        // Today that's only the default (English); as values-<lang>/strings.xml
        // files are added, extend this list. "System default" is always offered.
        _uiState.update {
            it.copy(
                languageOptions = listOf(
                    AppLanguageOption(tag = null, displayName = "System default"),
                    AppLanguageOption(tag = "en", displayName = "English")
                )
            )
        }
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

    /** (Re)reads the currently-available hardware devices. Safe to call on resume. */
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
        // Persist, then apply to the JVM default locale (both targets are JVM).
        // Visible UI re-localization additionally requires bundled translations
        // and, on some platforms, an app restart.
        launchLogged {
            appPreferences.setAppLanguageTag(tag)
            applyLocale(tag)
        }
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

    fun updateLanguageNames() {
        if (_uiState.value.langNamesUpdateState is LangNamesUpdateState.InProgress) return
        val url = _uiState.value.langNamesUrl.ifBlank { DEFAULT_LANG_NAMES_URL }
        launchLogged(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(langNamesUpdateState = LangNamesUpdateState.InProgress) }
            }
            val result = runCatching { importLanguages.update(url).await() }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        langNamesUpdateState = if (result.isSuccess) {
                            LangNamesUpdateState.Success
                        } else {
                            LangNamesUpdateState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
                        }
                    )
                }
            }
        }
    }

    fun dismissLangNamesResult() {
        _uiState.update { it.copy(langNamesUpdateState = LangNamesUpdateState.Idle) }
    }
}
