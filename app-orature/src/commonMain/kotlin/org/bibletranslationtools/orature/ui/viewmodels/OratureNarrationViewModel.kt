package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.ui.workbook.OratureWorkbookDataStore
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** One cell in the chapter-selector grid (JVM: `ChapterGridItemData`). */
data class OratureChapterGridItem(
    val sort: Int,
    /** The chapter number/title shown on the button (JVM: `chapter.title`). */
    val title: String,
    val completed: Boolean,
    val selected: Boolean
)

data class OratureNarrationUiState(
    val isLoading: Boolean = true,
    /** Raw target book title (screen formats it with the `narrationTitle` string). */
    val bookTitle: String = "",
    /** Active chapter number/title (screen formats it with the `chapterTitle` string). */
    val activeChapterTitle: String = "",
    val activeChapterSort: Int? = null,
    val chapters: List<OratureChapterGridItem> = emptyList(),
    val hasPreviousChapter: Boolean = false,
    val hasNextChapter: Boolean = false,
    val error: String? = null
)

/**
 * Drives the Orature narration page SHELL (Phase 4): loads the [org.bibletranslationtools.otter.common.data.workbook.Workbook]
 * for a clicked book, publishes it to the shared [OratureWorkbookDataStore], and exposes the
 * header state — book title, active chapter title, the chapter-selector grid, and prev/next
 * availability. Chapter selection here is fully live (JVM: `NarrationHeaderViewModel`); the
 * narration BODY (audio workspace / teleprompter / recording) arrives in Phase 5.
 *
 * The "completed" grid flag uses the lightweight [Chapter.hasSelectedAudio] proxy; Phase 5
 * replaces it with the real `ProjectCompletionStatus.getChapterNarrationProgress == 1.0`
 * (the JVM signal) once narration state is wired.
 */
class OratureNarrationViewModel(
    private val workbookDescriptorId: Int
) : ViewModel(), KoinComponent {

    private val workbookDescriptorRepo: IWorkbookDescriptorRepository by inject()
    private val workbookRepository: IWorkbookRepository by inject()
    private val workbookDataStore: OratureWorkbookDataStore by inject()

    private val _uiState = MutableStateFlow(OratureNarrationUiState())
    val uiState: StateFlow<OratureNarrationUiState> = _uiState.asStateFlow()

    /** Sorted chapters for the active workbook, cached for prev/next stepping. */
    private var chapters: List<Chapter> = emptyList()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = OratureNarrationUiState(isLoading = true)
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val descriptor = workbookDescriptorRepo.getByIdSuspend(workbookDescriptorId)
                        ?: error("No workbook descriptor with id=$workbookDescriptorId")
                    val workbook = workbookRepository.get(
                        descriptor.sourceCollection,
                        descriptor.targetCollection
                    )
                    val chapterList = workbook.target.chapters.toList().await().sortedBy { it.sort }
                    // Snapshot the lightweight completion proxy off the main thread.
                    val completed = chapterList.associate { it.sort to it.hasSelectedAudio() }
                    LoadResult(workbook, descriptor.mode, chapterList, completed)
                }

                workbookDataStore.open(loaded.workbook, loaded.mode)
                chapters = loaded.chapters

                // Restore the last-viewed chapter for this workbook, else the first (JVM behavior).
                val restoredSort = workbookDataStore.lastChapterSort(workbookDescriptorId)
                val active = chapters.firstOrNull { it.sort == restoredSort }
                    ?: chapters.firstOrNull()

                if (active != null) {
                    workbookDataStore.setActiveChapter(active, workbookDescriptorId)
                }

                _uiState.value = OratureNarrationUiState(
                    isLoading = false,
                    bookTitle = loaded.workbook.target.title.ifEmpty { loaded.workbook.target.slug.uppercase() },
                    activeChapterTitle = active?.title.orEmpty(),
                    activeChapterSort = active?.sort,
                    chapters = buildGrid(active?.sort, loaded.completed),
                    hasPreviousChapter = hasNeighbor(active?.sort, step = -1),
                    hasNextChapter = hasNeighbor(active?.sort, step = +1)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = OratureNarrationUiState(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    /** Select a chapter by its sort number (JVM: `NavigateChapterEvent`). */
    fun selectChapter(sort: Int) {
        val chapter = chapters.firstOrNull { it.sort == sort } ?: return
        workbookDataStore.setActiveChapter(chapter, workbookDescriptorId)
        val current = _uiState.value
        _uiState.value = current.copy(
            activeChapterTitle = chapter.title,
            activeChapterSort = chapter.sort,
            chapters = current.chapters.map { it.copy(selected = it.sort == sort) },
            hasPreviousChapter = hasNeighbor(sort, step = -1),
            hasNextChapter = hasNeighbor(sort, step = +1)
        )
    }

    fun selectPreviousChapter() = stepChapter(step = -1)

    fun selectNextChapter() = stepChapter(step = +1)

    private fun stepChapter(step: Int) {
        val activeSort = _uiState.value.activeChapterSort ?: return
        val index = chapters.indexOfFirst { it.sort == activeSort }
        chapters.getOrNull(index + step)?.let { selectChapter(it.sort) }
    }

    private fun hasNeighbor(activeSort: Int?, step: Int): Boolean {
        if (activeSort == null) return false
        val index = chapters.indexOfFirst { it.sort == activeSort }
        return index >= 0 && chapters.getOrNull(index + step) != null
    }

    private fun buildGrid(activeSort: Int?, completed: Map<Int, Boolean>): List<OratureChapterGridItem> =
        chapters.map { chapter ->
            OratureChapterGridItem(
                sort = chapter.sort,
                title = chapter.title,
                completed = completed[chapter.sort] ?: false,
                selected = chapter.sort == activeSort
            )
        }

    private class LoadResult(
        val workbook: org.bibletranslationtools.otter.common.data.workbook.Workbook,
        val mode: org.bibletranslationtools.otter.common.data.primitives.ProjectMode,
        val chapters: List<Chapter>,
        val completed: Map<Int, Boolean>
    )
}
