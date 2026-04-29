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
        dataStore.edit { it.clear() }
    }

    companion object {
        private val KEY_SOURCE_ID = intPreferencesKey("active_workbook_source_id")
        private val KEY_TARGET_ID = intPreferencesKey("active_workbook_target_id")
        private val KEY_CHAPTER_SORT = intPreferencesKey("active_chapter_sort")
        private val KEY_UNIT_SORT = intPreferencesKey("active_unit_sort")
    }
}
