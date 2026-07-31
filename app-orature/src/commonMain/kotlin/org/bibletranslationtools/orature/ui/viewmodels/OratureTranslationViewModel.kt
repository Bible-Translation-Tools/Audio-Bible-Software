package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.errOpenProject
import org.bibletranslationtools.orature.services.OratureWorkbookDataStore
import org.bibletranslationtools.orature.ui.translation.ChunkingStep
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.otter.common.domain.project.InitializeProjectFiles
import org.bibletranslationtools.otter.common.domain.project.OpenWorkbook
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** One chunk in the steps-drawer sub-list (JVM: `ChunkViewData`). */
data class OratureChunkViewData(
    val number: Int,
    val completed: Boolean,
    val selected: Boolean
)

/** UI state for the oral-translation page shell (JVM: `TranslationViewModel2` + header/drawer). */
/** What a requested step change should do — see [chunkNavAction]. */
internal enum class ChunkNavAction {
    /** Switch steps without touching chunks (never destructive). */
    NAVIGATE,
    /** Commit chunk edits (destructive: deletes the chapter's takes) and then switch. */
    SAVE_THEN_NAVIGATE,
    /** Ask first — committing would delete existing recordings. */
    CONFIRM_DATA_LOSS
}

/**
 * The chunk-navigation rules, mirroring the JVM (`ChunkingViewModel.requestToNavigate` +
 * `undock`). Committing chunk edits runs `resetChapter`, which DELETES the chapter's takes, so:
 *  - only moving FORWARD out of Chunking may commit (going back to Consume must not);
 *  - and if that commit would destroy existing recordings ([existingChunkCount] > 0) while there
 *    are unsaved edits, the user must confirm first — cancelling leaves the edits unsaved so they
 *    can be undone with the takes intact.
 */
internal fun chunkNavAction(
    current: ChunkingStep,
    target: ChunkingStep,
    hasUnsavedChunkEdits: Boolean,
    existingChunkCount: Int
): ChunkNavAction {
    val leavingChunkingForward =
        current == ChunkingStep.CHUNKING && target.ordinal > ChunkingStep.CHUNKING.ordinal
    if (!leavingChunkingForward) return ChunkNavAction.NAVIGATE
    if (hasUnsavedChunkEdits && existingChunkCount > 0) return ChunkNavAction.CONFIRM_DATA_LOSS
    return ChunkNavAction.SAVE_THEN_NAVIGATE
}

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
     * from chapter/chunk progress in [updateReachableStep]). Starts at CHUNKING (only Consume +
     * Chunking open) until chunks exist and gain audio/checking status.
     */
    val reachableStep: ChunkingStep = ChunkingStep.CHUNKING,
    /** The chapter's chunks (populated once chunking is done); drives the drawer sub-lists. */
    val chunks: List<OratureChunkViewData> = emptyList(),
    /** The active chunk's sort number (the chunk shown in Blind Draft / later steps), or null. */
    val activeChunkSort: Int? = null,
    /** True when the source has no audio for this chapter (Consume → SourceAudioMissing). */
    val noSourceAudio: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /**
     * Set when the user tried to navigate FORWARD out of Chunking with unsaved chunk edits while the
     * chapter already has chunks (and therefore recordings) that the save would destroy. Holds the
     * step they asked for until they confirm or cancel (JVM: `ChunkingViewModel.requestToNavigate`'s
     * `rechunk_data_loss_warning` ConfirmDialog). Cancelling leaves the chunk edits unsaved, so the
     * user can undo them and keep their existing takes.
     */
    val pendingChunkNavStep: ChunkingStep? = null,
    /** Source scripture text for the right-hand drawer. */
    val sourceText: String = "",
    /** The source resource's title (JVM: `SourceTextDrawer.sourceInfoProperty`), e.g.
     *  "English Unlocked Literal Bible (Audio)" — shown as the drawer's content heading. */
    val sourceTitle: String = "",
    /** The source resource's license identifier (JVM: `licenseProperty`), e.g. "CC BY-SA 4.0". */
    val sourceLicense: String = "",
    /** The verse label (e.g. "3" or "3-4") to highlight in the source-text drawer, tracking
     *  whichever step's playhead is active right now (JVM: `SourceTextDrawer.highlightedChunk`,
     *  fed via `TranslationViewModel2.currentMarkerProperty` from Consume/Final Review). Null when
     *  no step with a full-chapter source view is active, or the playhead precedes any verse. */
    val highlightedVerseLabel: String? = null,
    /** True while an external editor plugin has a take open (JVM: `shouldBlockWindowCloseRequest`/
     *  `externalPluginOpenedProperty` — there it blocks the OS window close; here, since there's no
     *  separate window, it blocks in-app navigation instead: switching steps, chapters, or leaving
     *  the translation page). Set by whichever step has a plugin open (currently Final Review). */
    val pluginOpen: Boolean = false,
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

    private val openWorkbook: OpenWorkbook by inject()
    private val workbookDataStore: OratureWorkbookDataStore by inject()

    private val _uiState = MutableStateFlow(OratureTranslationUiState())
    val uiState: StateFlow<OratureTranslationUiState> = _uiState.asStateFlow()

    private var chapters: List<Chapter> = emptyList()
    private var chunkJob: kotlinx.coroutines.Job? = null
    private var reachableJob: kotlinx.coroutines.Job? = null

    private data class LoadResult(
        val bookTitle: String,
        val mode: ProjectMode,
        val chapters: List<Chapter>,
        val completed: Map<Int, Boolean>,
        val noSourceAudio: Boolean,
        val sourceTitle: String,
        val sourceLicense: String
    )

    /** True when the source has no audio for [sort] (drives Consume → SourceAudioMissing). */
    private fun hasNoSourceAudio(sort: Int?): Boolean {
        if (sort == null) return true
        val wb = workbookDataStore.activeWorkbook.value ?: return true
        return runCatching { wb.sourceAudioAccessor.getChapter(sort, wb.target) }.getOrNull() == null
    }

    init {
        load()
        // Keep the right-hand source-text drawer in sync with the active chunk (JVM: sourceTextBinding
        // → getChunkSourceText). Shown on Peer-Edit-and-later steps and when source audio is missing.
        launchLogged {
            workbookDataStore.activeChunk.collect { chunk -> updateSourceText(chunk) }
        }
    }

    /** Load the source scripture text for [chunk] (or the chapter when null) into the drawer. */
    private fun updateSourceText(chunk: org.bibletranslationtools.otter.common.data.workbook.Chunk?) {
        val wb = workbookDataStore.activeWorkbook.value ?: return
        val chapterSort = _uiState.value.activeChapterSort
            ?: workbookDataStore.activeChapter.value?.sort ?: return
        launchLogged {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val accessor = wb.projectFilesAccessor
                    val slug = wb.source.slug
                    val verses = if (chunk != null) {
                        accessor.getChunkText(slug, chapterSort, chunk.start, chunk.end)
                    } else {
                        accessor.getChapterText(slug, chapterSort)
                    }
                    buildString { verses.forEach { append(it); append("\n") } }
                }.getOrDefault("")
            }
            _uiState.value = _uiState.value.copy(sourceText = text)
        }
    }

    private fun load() {
        launchLogged {
            _uiState.value = OratureTranslationUiState(isLoading = true)
            try {
                // Descriptor lookup, workbook resolution, chapter ordering and completion come
                // from OpenWorkbook, which dispatches to IO itself. What stays here is the
                // app-side work that is still file I/O: scaffolding the project files and
                // probing the source RC for chapter audio.
                val opened = openWorkbook.openWithChapters(workbookDescriptorId)
                val scaffolded = workbookDataStore.open(opened.workbook, opened.mode)
                if (scaffolded is InitializeProjectFiles.Result.Failed) {
                    logFailure("scaffolding project files (${scaffolded.step})", scaffolded.cause)
                    // Chunking loads the project RC to copy source audio, so a missing manifest.yaml
                    // would surface there instead of here, looking like an unrelated failure.
                    if (!scaffolded.projectUsable) {
                        _uiState.value = OratureTranslationUiState(
                            isLoading = false,
                            error = getString(Res.string.errOpenProject)
                        )
                        return@launchLogged
                    }
                }
                val loaded = withContext(Dispatchers.IO) {
                    val workbook = opened.workbook
                    val restoredSort = workbookDataStore.lastChapterSort(workbookDescriptorId)
                    val activeSort = (opened.chapters.firstOrNull { it.sort == restoredSort }
                        ?: opened.chapters.firstOrNull())?.sort
                    val noSource = runCatching {
                        activeSort != null &&
                            workbook.sourceAudioAccessor.getChapter(activeSort, workbook.target) == null
                    }.getOrDefault(true)
                    LoadResult(
                        workbook.target.title.ifEmpty { workbook.target.slug.uppercase() },
                        opened.mode,
                        opened.chapters,
                        opened.completedByChapterSort,
                        noSource,
                        sourceTitle = workbook.source.resourceMetadata.title,
                        sourceLicense = workbook.source.resourceMetadata.license
                    )
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
                    noSourceAudio = loaded.noSourceAudio,
                    sourceTitle = loaded.sourceTitle,
                    sourceLicense = loaded.sourceLicense
                )
                updateReachableStep()
                if (active != null) resumeStepForChapter(active, active.sort)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading the translation screen", e)
                _uiState.value = OratureTranslationUiState(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Jump to the furthest step this chapter's chunks have actually reached (JVM: navigateChapter →
     * `updateStep { selectedStepProperty.set(if (reachable == CHUNKING) CONSUME_AND_VERBALIZE else
     * reachable) }`), instead of always opening on Consume. Runs ONCE per chapter (re)load — not on
     * every later [updateReachableStep] recomputation — so it doesn't yank the user back to an
     * earlier step mid-work just because chunk progress changed underneath them.
     */
    private fun resumeStepForChapter(chapter: Chapter, sort: Int) {
        launchLogged {
            val reachable = withContext(Dispatchers.IO) {
                val chunkList = runCatching {
                    chapter.chunks.blockingGet().filter { it.contentType == ContentType.TEXT }
                }.getOrDefault(emptyList())
                computeReachableStep(chunkList)
            }
            if (_uiState.value.activeChapterSort != sort) return@launchLogged // chapter changed again meanwhile
            val resumedStep = if (reachable == ChunkingStep.CHUNKING) {
                ChunkingStep.CONSUME_AND_VERBALIZE
            } else {
                reachable
            }
            _uiState.value = _uiState.value.copy(selectedStep = resumedStep)
            if (resumedStep.ordinal >= ChunkingStep.BLIND_DRAFT.ordinal) loadChunks()
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

    // Final Review registers its "open in external editor" action here so the header's Open-In
    // button (JVM: OpenInPluginEvent) drives it, mirroring the undo/redo handler pattern above.
    private var openInHandler: (() -> Unit)? = null

    fun setOpenInHandler(handler: () -> Unit) {
        openInHandler = handler
    }

    fun clearOpenInHandler() {
        openInHandler = null
    }

    fun onOpenIn() { openInHandler?.invoke() }

    /** Lock/unlock in-app navigation while an external editor plugin has a take open (JVM:
     *  the window-close guard, adapted — see [OratureTranslationUiState.pluginOpen]). */
    fun setPluginOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(pluginOpen = open)
    }

    /** Set by whichever step tracks a full-chapter playhead (JVM: binding `currentMarkerProperty`
     *  to that step's `highlightedMarkerIndexProperty`) so the source-text drawer's highlight
     *  follows along. Pass null to clear (e.g. when that step unmounts). */
    fun setHighlightedVerse(label: String?) {
        if (_uiState.value.highlightedVerseLabel != label) {
            _uiState.value = _uiState.value.copy(highlightedVerseLabel = label)
        }
    }

    // The active Chunking VM registers an awaited save here so leaving the step persists its chunks
    // (and they're committed + readable) BEFORE the next step loads — matching Orature's undock save.
    private var chunkSaveHandler: (suspend () -> Unit)? = null
    fun setChunkSaveHandler(handler: suspend () -> Unit) { chunkSaveHandler = handler }
    fun clearChunkSaveHandler() { chunkSaveHandler = null }

    /** Reload after an import (source audio may now exist → recompute noSourceAudio). */
    fun refresh() {
        // Re-fetch the workbook so the source-audio accessor rescans (its cache is per-instance).
        load()
    }

    /**
     * Navigate to a step (JVM: navigateStep) — only if it is reachable and no plugin is open. Always
     * resets the active chunk back to the first one: switching steps (e.g. Blind Draft chunk 3 →
     * Peer Edit) should start fresh at chunk 1 rather than carry over whichever chunk the previous
     * step happened to be on (clearing `activeChunkSort` makes [applyChunkState]'s fallback resolve
     * to the first chunk once [loadChunks] re-subscribes).
     */
    fun selectStep(step: ChunkingStep) {
        val s = _uiState.value
        if (s.pluginOpen) return
        if (step.ordinal > s.reachableStep.ordinal) return

        // Committing chunk edits is DESTRUCTIVE (resetChapter deletes the chapter's takes before
        // re-creating chunks), so navigating FORWARD out of Chunking with unsaved edits must be
        // confirmed when the chapter already has chunks. Until the user confirms we neither save nor
        // navigate — that's what lets them cancel, undo the chunk move, and keep their takes.
        // chunkCount is only read when it can matter (a forward leave with unsaved edits), so the
        // common path stays synchronous.
        val mayNeedConfirm = chunkNavAction(s.selectedStep, step, s.canUndo, existingChunkCount = 1) ==
            ChunkNavAction.CONFIRM_DATA_LOSS
        if (mayNeedConfirm) {
            launchLogged {
                val action = chunkNavAction(s.selectedStep, step, s.canUndo, activeChapterChunkCount())
                if (action == ChunkNavAction.CONFIRM_DATA_LOSS) {
                    _uiState.value = _uiState.value.copy(pendingChunkNavStep = step)
                } else {
                    commitStepNavigation(step) // nothing recorded yet — nothing to lose
                }
            }
            return
        }
        commitStepNavigation(step)
    }

    /** Proceed with a step change, saving Chunking's edits first when moving FORWARD past it. */
    private fun commitStepNavigation(step: ChunkingStep) {
        val s = _uiState.value
        // Save ONLY when navigating forward past Chunking (JVM `undock()`'s
        // `selectedStep.ordinal > CHUNKING.ordinal`). Going backward (e.g. to Consume) must not run
        // the destructive reset — previously any step change out of Chunking saved, silently
        // destroying takes. The handler itself no-ops when there are no edits.
        val saveFirst = chunkNavAction(
            s.selectedStep, step, hasUnsavedChunkEdits = s.canUndo, existingChunkCount = 0
        ) == ChunkNavAction.SAVE_THEN_NAVIGATE && chunkSaveHandler != null
        if (saveFirst) {
            // Persist and WAIT before switching, so the next step reads committed chunk content
            // (JVM saves synchronously in undock before navigating).
            launchLogged {
                runCatching { chunkSaveHandler?.invoke() }
                _uiState.value = _uiState.value.copy(
                    selectedStep = step,
                    activeChunkSort = null,
                    pendingChunkNavStep = null
                )
                if (step.ordinal >= ChunkingStep.BLIND_DRAFT.ordinal) loadChunks()
            }
        } else {
            _uiState.value = s.copy(
                selectedStep = step,
                activeChunkSort = null,
                pendingChunkNavStep = null
            )
            if (step.ordinal >= ChunkingStep.BLIND_DRAFT.ordinal) loadChunks()
        }
    }

    /** The user accepted losing this chapter's recordings — commit the chunk edits and navigate. */
    fun confirmPendingChunkNav() {
        val step = _uiState.value.pendingChunkNavStep ?: return
        commitStepNavigation(step)
    }

    /** Dismiss the warning and stay on Chunking with the edits still UNSAVED (so undo can restore
     *  the previous chunk layout and the existing takes remain intact). */
    fun cancelPendingChunkNav() {
        _uiState.value = _uiState.value.copy(pendingChunkNavStep = null)
    }

    /** The active chapter's existing chunk count (JVM: `workbookDataStore.chapter.chunkCount`) —
     *  non-zero means there are chunks whose takes a chunk-edit save would delete. */
    private suspend fun activeChapterChunkCount(): Int {
        val chapterSort = _uiState.value.activeChapterSort ?: return 0
        val chapter = chapters.firstOrNull { it.sort == chapterSort } ?: return 0
        return withContext(Dispatchers.IO) {
            runCatching { chapter.chunkCount.blockingGet() }.getOrDefault(0)
        }
    }

    /**
     * Subscribe to the active chapter's TEXT chunks (JVM: subscribeToChunks). Reactive because chunks
     * are created asynchronously when Chunking saves — a one-shot read would race that and find none.
     * Keeps the current chunk selected across emissions; auto-selects the first (unrecorded) chunk
     * when nothing is selected yet.
     */
    fun loadChunks() {
        val chapterSort = _uiState.value.activeChapterSort ?: return
        val chapter = chapters.firstOrNull { it.sort == chapterSort } ?: return
        chunkJob?.cancel()
        chunkJob = launchLogged {
            chapter.observableChunks.asFlow()
                .map { list -> list.filter { it.contentType == ContentType.TEXT } }
                .collect { chunkList -> applyChunkState(chunkList) }
        }
    }

    /**
     * A take was recorded / selected / deleted on a chunk step (JVM: refreshChunkList). Take
     * selection doesn't change content rows, so [observableChunks] won't re-emit — re-read the
     * current chunk state so the drawer completion + reachable step update.
     */
    fun onChunkTakesChanged() {
        val chapterSort = _uiState.value.activeChapterSort ?: return
        val chapter = chapters.firstOrNull { it.sort == chapterSort } ?: return
        launchLogged {
            val chunkList = withContext(Dispatchers.IO) {
                runCatching { chapter.chunks.blockingGet().filter { it.contentType == ContentType.TEXT } }
                    .getOrDefault(emptyList())
            }
            applyChunkState(chunkList)
        }
    }

    /** Publish the drawer chunk list (step-aware completion), keep a chunk active, and recompute the
     *  reachable step from the chunks' audio/checking status (JVM: loadChunks + updateStep). */
    private fun applyChunkState(chunkList: List<Chunk>) {
        val activeSort = _uiState.value.activeChunkSort
        val active = chunkList.firstOrNull { it.sort == activeSort }
            ?: chunkList.firstOrNull { !it.hasSelectedAudio() }
            ?: chunkList.firstOrNull()
        if (active?.sort != workbookDataStore.activeChunk.value?.sort) {
            workbookDataStore.setActiveChunk(active)
        }
        val step = _uiState.value.selectedStep
        _uiState.value = _uiState.value.copy(
            chunks = chunkList.map {
                OratureChunkViewData(it.sort, stepCompleted(it, step), it.sort == active?.sort)
            },
            activeChunkSort = active?.sort,
            reachableStep = computeReachableStep(chunkList)
        )
    }

    /** Whether a chunk counts as "done" for the drawer checkmark at [step] (JVM: loadChunks.completed). */
    private fun stepCompleted(chunk: Chunk, step: ChunkingStep): Boolean = when (step) {
        ChunkingStep.BLIND_DRAFT -> chunk.hasSelectedAudio()
        ChunkingStep.PEER_EDIT -> chunk.checkingStatus().ordinal >= CheckingStatus.PEER_EDIT.ordinal
        ChunkingStep.KEYWORD_CHECK -> chunk.checkingStatus().ordinal >= CheckingStatus.KEYWORD.ordinal
        ChunkingStep.VERSE_CHECK -> chunk.checkingStatus().ordinal >= CheckingStatus.VERSE.ordinal
        else -> false
    }

    /**
     * Keep [OratureTranslationUiState.reachableStep] current for the active chapter (JVM: updateStep).
     * Runs independently of the selected step so the steps drawer unlocks Blind Draft the moment
     * chunks are created, then Peer Edit once every chunk has selected audio, and so on. Re-created on
     * each chapter change; take-selection changes (which don't re-emit here) go through
     * [onChunkTakesChanged].
     */
    private fun updateReachableStep() {
        val chapterSort = _uiState.value.activeChapterSort ?: return
        val chapter = chapters.firstOrNull { it.sort == chapterSort } ?: return
        reachableJob?.cancel()
        reachableJob = launchLogged {
            chapter.observableChunks.asFlow()
                .map { list -> list.filter { it.contentType == ContentType.TEXT } }
                .collect { chunkList ->
                    _uiState.value = _uiState.value.copy(reachableStep = computeReachableStep(chunkList))
                }
        }
    }

    /** The furthest reachable step given the chunks' progress (JVM: updateStep). */
    private fun computeReachableStep(chunkList: List<Chunk>): ChunkingStep = when {
        chunkList.isEmpty() -> ChunkingStep.CHUNKING
        chunkList.all { it.checkingStatus() == CheckingStatus.VERSE } -> ChunkingStep.FINAL_REVIEW
        chunkList.all { it.checkingStatus().ordinal >= CheckingStatus.KEYWORD.ordinal } -> ChunkingStep.VERSE_CHECK
        chunkList.all { it.checkingStatus().ordinal >= CheckingStatus.PEER_EDIT.ordinal } -> ChunkingStep.KEYWORD_CHECK
        chunkList.all { it.hasSelectedAudio() } -> ChunkingStep.PEER_EDIT
        else -> ChunkingStep.BLIND_DRAFT
    }

    /** Select a chunk by sort (JVM: selectChunk) — drives the Blind Draft / later step bodies. */
    fun selectChunk(sort: Int) {
        if (_uiState.value.pluginOpen) return
        val chapterSort = _uiState.value.activeChapterSort ?: return
        val chapter = chapters.firstOrNull { it.sort == chapterSort } ?: return
        launchLogged {
            val chunk = withContext(Dispatchers.IO) {
                runCatching { chapter.chunks.blockingGet().firstOrNull { it.sort == sort } }.getOrNull()
            } ?: return@launchLogged
            workbookDataStore.setActiveChunk(chunk)
            _uiState.value = _uiState.value.copy(
                chunks = _uiState.value.chunks.map { it.copy(selected = it.number == sort) },
                activeChunkSort = sort
            )
        }
    }

    fun selectChapter(sort: Int) {
        if (_uiState.value.pluginOpen) return
        val chapter = chapters.firstOrNull { it.sort == sort } ?: return
        workbookDataStore.setActiveChapter(chapter, workbookDescriptorId)
        val current = _uiState.value
        _uiState.value = current.copy(
            activeChapterTitle = chapter.title,
            activeChapterSort = chapter.sort,
            chapters = current.chapters.map { it.copy(selected = it.sort == sort) },
            hasPreviousChapter = hasNeighbor(sort, step = -1),
            hasNextChapter = hasNeighbor(sort, step = +1),
            // Transient placeholder while the resumed step is computed below (JVM: navigateChapter
            // clears selectedStepProperty, then updateStep's callback sets it once chunk progress
            // is known) — resumeStepForChapter corrects this to wherever the chapter's progress
            // actually left off, matching JVM instead of always reopening on Consume.
            selectedStep = ChunkingStep.CONSUME_AND_VERBALIZE
        )
        updateReachableStep()
        launchLogged {
            val noSource = withContext(Dispatchers.IO) { hasNoSourceAudio(sort) }
            if (_uiState.value.activeChapterSort == sort) {
                _uiState.value = _uiState.value.copy(noSourceAudio = noSource)
            }
        }
        resumeStepForChapter(chapter, sort)
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
