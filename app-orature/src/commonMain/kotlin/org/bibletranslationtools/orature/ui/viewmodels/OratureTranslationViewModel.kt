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
import org.bibletranslationtools.orature.ui.translation.ChunkingStep
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** One chunk in the steps-drawer sub-list (JVM: `ChunkViewData`). */
data class OratureChunkViewData(
    val number: Int,
    val title: String,
    val reachable: Boolean,
    val completed: Boolean
)

/** UI state for the oral-translation page shell (JVM: `TranslationViewModel2` + header/drawer). */
data class OratureTranslationUiState(
    val isLoading: Boolean = true,
    val bookTitle: String = "",
    val activeChapterTitle: String = "",
    val activeChapterSort: Int? = null,
    val chapters: List<OratureChapterGridItem> = emptyList(),
    val hasPreviousChapter: Boolean = false,
    val hasNextChapter: Boolean = false,
    /** The step whose screen is shown in the center. */
    val selectedStep: ChunkingStep = ChunkingStep.CONSUME_AND_VERBALIZE,
    /**
     * The furthest step the user may open; later steps are locked (JVM: reachableStep, computed
     * from chapter/chunk progress). TODO(6b/6c): derive from progress. Temporarily all-reachable so
     * the shell is fully navigable.
     */
    val reachableStep: ChunkingStep = ChunkingStep.FINAL_REVIEW,
    /** The chapter's chunks (populated once chunking is done); drives the drawer sub-lists. */
    val chunks: List<OratureChunkViewData> = emptyList(),
    /** True when the source has no audio for this chapter (Consume → SourceAudioMissing). */
    val noSourceAudio: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Source scripture text for the right-hand drawer. */
    val sourceText: String = "",
    val error: String? = null
)

/**
 * Drives the Orature oral-translation page shell (Phase 6a): loads the workbook for a clicked
 * (translation-mode) book, publishes it to the shared [OratureWorkbookDataStore], and exposes the
 * header state (book/chapter, prev/next), the steps-drawer state (selected/reachable step, chunks,
 * no-source-audio), and step navigation. The step BODIES (Consume, Chunking, …) arrive in 6b/6c.
 */
class OratureTranslationViewModel(
    private val workbookDescriptorId: Int
) : ViewModel(), KoinComponent {

    private val workbookDescriptorRepo: IWorkbookDescriptorRepository by inject()
    private val workbookRepository: IWorkbookRepository by inject()
    private val workbookDataStore: OratureWorkbookDataStore by inject()

    private val _uiState = MutableStateFlow(OratureTranslationUiState())
    val uiState: StateFlow<OratureTranslationUiState> = _uiState.asStateFlow()

    private var chapters: List<Chapter> = emptyList()

    private data class LoadResult(
        val bookTitle: String,
        val mode: ProjectMode,
        val chapters: List<Chapter>,
        val completed: Map<Int, Boolean>,
        val noSourceAudio: Boolean
    )

    /** True when the source has no audio for [sort] (drives Consume → SourceAudioMissing). */
    private fun hasNoSourceAudio(sort: Int?): Boolean {
        if (sort == null) return true
        val wb = workbookDataStore.activeWorkbook.value ?: return true
        return runCatching { wb.sourceAudioAccessor.getChapter(sort, wb.target) }.getOrNull() == null
    }

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = OratureTranslationUiState(isLoading = true)
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val descriptor = workbookDescriptorRepo.getByIdSuspend(workbookDescriptorId)
                        ?: error("No workbook descriptor with id=$workbookDescriptorId")
                    val workbook = workbookRepository.get(
                        descriptor.sourceCollection,
                        descriptor.targetCollection
                    )
                    workbookDataStore.open(workbook, descriptor.mode)
                    val chapterList = workbook.target.chapters.toList().await().sortedBy { it.sort }
                    val completed = chapterList.associate { it.sort to it.hasSelectedAudio() }
                    val title = workbook.target.title.ifEmpty { workbook.target.slug.uppercase() }
                    val restoredSort = workbookDataStore.lastChapterSort(workbookDescriptorId)
                    val activeSort = (chapterList.firstOrNull { it.sort == restoredSort }
                        ?: chapterList.firstOrNull())?.sort
                    val noSource = runCatching {
                        activeSort != null &&
                            workbook.sourceAudioAccessor.getChapter(activeSort, workbook.target) == null
                    }.getOrDefault(true)
                    LoadResult(title, descriptor.mode, chapterList, completed, noSource)
                }

                chapters = loaded.chapters
                val restoredSort = workbookDataStore.lastChapterSort(workbookDescriptorId)
                val active = chapters.firstOrNull { it.sort == restoredSort } ?: chapters.firstOrNull()
                if (active != null) workbookDataStore.setActiveChapter(active, workbookDescriptorId)

                _uiState.value = OratureTranslationUiState(
                    isLoading = false,
                    bookTitle = loaded.bookTitle,
                    activeChapterTitle = active?.title.orEmpty(),
                    activeChapterSort = active?.sort,
                    chapters = buildGrid(active?.sort, loaded.completed),
                    hasPreviousChapter = hasNeighbor(active?.sort, step = -1),
                    hasNextChapter = hasNeighbor(active?.sort, step = +1),
                    noSourceAudio = loaded.noSourceAudio
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = OratureTranslationUiState(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    // The active step's undo/redo (currently Chunking) registers handlers here so the page header's
    // undo/redo drive it (JVM: the chunking VM writes translationViewModel's can-undo/redo).
    private var undoHandler: (() -> Unit)? = null
    private var redoHandler: (() -> Unit)? = null

    fun setUndoRedoHandlers(undo: () -> Unit, redo: () -> Unit) {
        undoHandler = undo
        redoHandler = redo
    }

    fun clearUndoRedoHandlers() {
        undoHandler = null
        redoHandler = null
        _uiState.value = _uiState.value.copy(canUndo = false, canRedo = false)
    }

    fun updateChunkUndoRedo(canUndo: Boolean, canRedo: Boolean) {
        _uiState.value = _uiState.value.copy(canUndo = canUndo, canRedo = canRedo)
    }

    fun onUndo() { undoHandler?.invoke() }
    fun onRedo() { redoHandler?.invoke() }

    /** Reload after an import (source audio may now exist → recompute noSourceAudio). */
    fun refresh() {
        // Re-fetch the workbook so the source-audio accessor rescans (its cache is per-instance).
        load()
    }

    /** Navigate to a step (JVM: navigateStep) — only if it is reachable. */
    fun selectStep(step: ChunkingStep) {
        val s = _uiState.value
        if (step.ordinal <= s.reachableStep.ordinal) {
            _uiState.value = s.copy(selectedStep = step)
        }
    }

    fun selectChapter(sort: Int) {
        val chapter = chapters.firstOrNull { it.sort == sort } ?: return
        workbookDataStore.setActiveChapter(chapter, workbookDescriptorId)
        val current = _uiState.value
        _uiState.value = current.copy(
            activeChapterTitle = chapter.title,
            activeChapterSort = chapter.sort,
            chapters = current.chapters.map { it.copy(selected = it.sort == sort) },
            hasPreviousChapter = hasNeighbor(sort, step = -1),
            hasNextChapter = hasNeighbor(sort, step = +1),
            // Reset the workflow to the first step for the newly-selected chapter.
            selectedStep = ChunkingStep.CONSUME_AND_VERBALIZE
        )
        viewModelScope.launch {
            val noSource = withContext(Dispatchers.IO) { hasNoSourceAudio(sort) }
            if (_uiState.value.activeChapterSort == sort) {
                _uiState.value = _uiState.value.copy(noSourceAudio = noSource)
            }
        }
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
}
