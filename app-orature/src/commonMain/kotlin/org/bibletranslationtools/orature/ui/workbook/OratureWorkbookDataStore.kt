package org.bibletranslationtools.orature.ui.workbook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook

/**
 * Orature's central open-project state, shared across the mode-page components (header,
 * chapter grid, and — from Phase 5 — the audio workspace / teleprompter). This is the
 * Compose port of the JVM app's `WorkbookDataStore` (a scoped singleton holding the active
 * workbook / chapter / chunk / mode). Registered as a Koin `single`; the narration screen's
 * ViewModel writes it, downstream components read it.
 *
 * Unlike the JavaFX original (JavaFX `SimpleObjectProperty`), state is exposed as
 * [StateFlow] for Compose collection. Only the active *selection* lives here; per-screen UI
 * derivations (titles, grid rows) belong in the screen ViewModel.
 */
class OratureWorkbookDataStore(
    private val workbookRepository: IWorkbookRepository
) {
    private val _activeWorkbook = MutableStateFlow<Workbook?>(null)
    val activeWorkbook: StateFlow<Workbook?> = _activeWorkbook.asStateFlow()

    private val _activeChapter = MutableStateFlow<Chapter?>(null)
    val activeChapter: StateFlow<Chapter?> = _activeChapter.asStateFlow()

    private val _activeChunk = MutableStateFlow<Chunk?>(null)
    val activeChunk: StateFlow<Chunk?> = _activeChunk.asStateFlow()

    private val _currentMode = MutableStateFlow<ProjectMode?>(null)
    val currentMode: StateFlow<ProjectMode?> = _currentMode.asStateFlow()

    /** Last-viewed chapter sort per workbook-descriptor id (JVM: `workbookRecentChapterMap`). */
    private val recentChapterByWorkbook = mutableMapOf<Int, Int>()

    /** The active workbook, or throw — mirrors the JVM `workbook` getter used by bound views. */
    val workbook: Workbook get() = _activeWorkbook.value ?: error("No active workbook")

    /** The active chapter, or throw. */
    val chapter: Chapter get() = _activeChapter.value ?: error("No active chapter")

    /**
     * Set the active workbook + mode (JVM: `activeWorkbookProperty` / `currentModeProperty`).
     * Closes the previously-open workbook (freeing its Rx connections) when switching projects,
     * matching the JVM app's cached `workbookRepo` lifecycle.
     */
    fun open(workbook: Workbook, mode: ProjectMode) {
        val previous = _activeWorkbook.value
        if (previous != null && previous !== workbook) {
            workbookRepository.closeWorkbook(previous)
        }
        _activeWorkbook.value = workbook
        _currentMode.value = mode
        _activeChapter.value = null
        _activeChunk.value = null
    }

    /** Set the active chapter and remember it for [workbookDescriptorId] (JVM: `updateLastSelectedChapter`). */
    fun setActiveChapter(chapter: Chapter, workbookDescriptorId: Int) {
        _activeChapter.value = chapter
        _activeChunk.value = null
        recentChapterByWorkbook[workbookDescriptorId] = chapter.sort
    }

    fun setActiveChunk(chunk: Chunk?) {
        _activeChunk.value = chunk
    }

    /** The chapter sort last viewed for this workbook, if any (restored on reopen). */
    fun lastChapterSort(workbookDescriptorId: Int): Int? = recentChapterByWorkbook[workbookDescriptorId]
}
