package org.bibletranslationtools.bttrecorder2.preferences

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

interface IAppPreferences {
    val navState: Flow<ActiveNavState>

    suspend fun setActiveWorkbook(sourceId: Int, targetId: Int)
    suspend fun setActiveChapter(sort: Int)
    suspend fun setActiveUnit(sort: Int)
    suspend fun clearActiveWorkbook()
}
