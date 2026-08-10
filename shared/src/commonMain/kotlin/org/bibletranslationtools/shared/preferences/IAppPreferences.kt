package org.bibletranslationtools.shared.preferences

import kotlinx.coroutines.flow.Flow

data class ActiveNavState(
    val workbookSourceId: Int = -1,
    val workbookTargetId: Int = -1,
    val chapterSort: Int = -1,
    val unitSort: Int = -1
) {
    val hasActiveWorkbook get() = workbookSourceId != -1 && workbookTargetId != -1
    val hasActiveChapter get() = hasActiveWorkbook && chapterSort != -1
    val hasActiveUnit get() = hasActiveChapter && unitSort != -1
}

/** Which color scheme the app renders with. SYSTEM follows the OS dark-mode setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * User-configurable application settings (the Settings screen). Distinct from
 * [ActiveNavState], which tracks transient navigation position.
 *
 * Audio devices are stored by their stable [org.bibletranslationtools.otter.common.device.AudioDevice.id]
 * so a remembered choice can be re-selected on next launch. A null id means
 * "no explicit choice — use the system default".
 *
 * [appLanguageTag] is a BCP-47 tag (e.g. "en", "es"); null/"" means follow the
 * system locale.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val outputDeviceId: String? = null,
    val inputDeviceId: String? = null,
    val appLanguageTag: String? = null,
    val langNamesUrl: String = DEFAULT_LANG_NAMES_URL
) {
    companion object {
        const val DEFAULT_LANG_NAMES_URL =
            "https://langnames.bibleineverylanguage.org/langnames.json"
    }
}

interface IAppPreferences {
    val navState: Flow<ActiveNavState>

    suspend fun setActiveWorkbook(sourceId: Int, targetId: Int)
    suspend fun setActiveChapter(sort: Int)
    suspend fun setActiveUnit(sort: Int)
    suspend fun clearActiveWorkbook()

    val appSettings: Flow<AppSettings>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setOutputDeviceId(id: String?)
    suspend fun setInputDeviceId(id: String?)
    suspend fun setAppLanguageTag(tag: String?)
    suspend fun setLangNamesUrl(url: String)
}
