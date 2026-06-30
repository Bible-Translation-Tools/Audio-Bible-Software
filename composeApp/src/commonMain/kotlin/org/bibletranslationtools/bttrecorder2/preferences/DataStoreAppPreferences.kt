package org.bibletranslationtools.bttrecorder2.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.*
import okio.Path.Companion.toPath

class DataStoreAppPreferences(dirPath: String) : IAppPreferences {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath {
        "$dirPath/app_navigation.preferences_pb".toPath()
    }

    override val navState: Flow<ActiveNavState> = dataStore.data
        .map { prefs ->
            ActiveNavState(
                workbookSourceId = prefs[KEY_SOURCE_ID] ?: -1,
                workbookTargetId = prefs[KEY_TARGET_ID] ?: -1,
                chapterSort = prefs[KEY_CHAPTER_SORT] ?: -1,
                unitSort = prefs[KEY_UNIT_SORT] ?: -1
            )
        }
        .catch { emit(ActiveNavState()) }

    override suspend fun setActiveWorkbook(sourceId: Int, targetId: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_SOURCE_ID] = sourceId
            prefs[KEY_TARGET_ID] = targetId
            prefs.remove(KEY_CHAPTER_SORT)
            prefs.remove(KEY_UNIT_SORT)
        }
    }

    override suspend fun setActiveChapter(sort: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_CHAPTER_SORT] = sort
            prefs.remove(KEY_UNIT_SORT)
        }
    }

    override suspend fun setActiveUnit(sort: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_UNIT_SORT] = sort
        }
    }

    override suspend fun clearActiveWorkbook() {
        // Only clear navigation keys — app settings (theme, devices, language)
        // must survive switching/closing a project.
        dataStore.edit { prefs ->
            prefs.remove(KEY_SOURCE_ID)
            prefs.remove(KEY_TARGET_ID)
            prefs.remove(KEY_CHAPTER_SORT)
            prefs.remove(KEY_UNIT_SORT)
        }
    }

    override val appSettings: Flow<AppSettings> = dataStore.data
        .map { prefs ->
            AppSettings(
                themeMode = prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                outputDeviceId = prefs[KEY_OUTPUT_DEVICE_ID],
                inputDeviceId = prefs[KEY_INPUT_DEVICE_ID],
                appLanguageTag = prefs[KEY_APP_LANGUAGE],
                langNamesUrl = prefs[KEY_LANG_NAMES_URL] ?: AppSettings.DEFAULT_LANG_NAMES_URL
            )
        }
        .catch { emit(AppSettings()) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    override suspend fun setOutputDeviceId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_OUTPUT_DEVICE_ID) else prefs[KEY_OUTPUT_DEVICE_ID] = id
        }
    }

    override suspend fun setInputDeviceId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_INPUT_DEVICE_ID) else prefs[KEY_INPUT_DEVICE_ID] = id
        }
    }

    override suspend fun setAppLanguageTag(tag: String?) {
        dataStore.edit { prefs ->
            if (tag.isNullOrBlank()) prefs.remove(KEY_APP_LANGUAGE) else prefs[KEY_APP_LANGUAGE] = tag
        }
    }

    override suspend fun setLangNamesUrl(url: String) {
        dataStore.edit { prefs ->
            if (url.isBlank()) prefs.remove(KEY_LANG_NAMES_URL) else prefs[KEY_LANG_NAMES_URL] = url
        }
    }

    companion object {
        private val KEY_SOURCE_ID = intPreferencesKey("active_workbook_source_id")
        private val KEY_TARGET_ID = intPreferencesKey("active_workbook_target_id")
        private val KEY_CHAPTER_SORT = intPreferencesKey("active_chapter_sort")
        private val KEY_UNIT_SORT = intPreferencesKey("active_unit_sort")

        private val KEY_THEME_MODE = stringPreferencesKey("settings_theme_mode")
        private val KEY_OUTPUT_DEVICE_ID = stringPreferencesKey("settings_output_device_id")
        private val KEY_INPUT_DEVICE_ID = stringPreferencesKey("settings_input_device_id")
        private val KEY_APP_LANGUAGE = stringPreferencesKey("settings_app_language")
        private val KEY_LANG_NAMES_URL = stringPreferencesKey("settings_lang_names_url")
    }
}
