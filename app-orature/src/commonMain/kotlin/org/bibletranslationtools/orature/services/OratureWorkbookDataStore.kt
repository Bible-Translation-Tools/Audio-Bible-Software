package org.bibletranslationtools.orature.services

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.InitializeProjectFiles
import kotlinx.coroutines.withContext

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
    private val workbookRepository: IWorkbookRepository,
    private val initializeProjectFiles: InitializeProjectFiles,
    private val chapterSession: OratureChapterSession
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
     * Set the active workbook + mode (JVM: `activeWorkbookProperty` / `currentModeProperty`) and
     * make sure the project's on-disk files exist.
     *
     * Closes the previously-open workbook when switching projects, so its changes in progress finish
     * and no later ones start.
     *
     * Every open first leaves the chapter in session ([OratureChapterSession.leave]), before the
     * previous workbook closes: opening a project, switching to another, and opening narration on
     * the book all end the chapter's undo history and make its rechunk stick.
     *
     * The scaffolding stays wired to this call rather than being left to each screen: two screens
     * open projects today, and anything that reaches `ResourceContainer.load(projectDir)` depends
     * on it having run. A caller that forgot would not fail here — it would fail later, elsewhere,
     * with a missing `manifest.yaml`. The work itself lives in [InitializeProjectFiles]; this only
     * decides when it runs.
     *
     * @return whether the scaffolding succeeded, and if not which step failed. Callers must not
     *   discard it: on a [InitializeProjectFiles.Result.Failed] with `projectUsable == false` the
     *   project should not be presented as open.
     */
    suspend fun open(workbook: Workbook, mode: ProjectMode): InitializeProjectFiles.Result {
        chapterSession.leave()
        val previous = _activeWorkbook.value
        if (previous != null && previous !== workbook) {
            workbookRepository.closeWorkbook(previous)
        }
        _activeWorkbook.value = workbook
        _currentMode.value = mode
        _activeChapter.value = null
        _activeChunk.value = null
        return initializeProjectFiles.execute(workbook, mode)
    }

    /**
     * Closes the open project: leaves the chapter in session, then closes its workbook, which waits
     * for a change being saved and refuses any after it. For closing the app.
     */
    suspend fun close() = withContext(NonCancellable) {
        chapterSession.leave()
        _activeWorkbook.value?.let { workbookRepository.closeWorkbook(it) }
        _activeWorkbook.value = null
        _activeChapter.value = null
        _activeChunk.value = null
    }

    /**
     * Closes [project]'s workbook before its files are deleted or reset, so nothing writes to the
     * project while it goes: the open one, and the repository's live copy if another screen opened
     * one. A project whose books cannot be built has no workbook to close, and must still be deletable.
     */
    suspend fun closeProject(project: WorkbookDescriptor) = withContext(NonCancellable) {
        val open = _activeWorkbook.value
        if (open != null &&
            open.source.collectionId == project.sourceCollection.id &&
            open.target.collectionId == project.targetCollection.id
        ) {
            close()
        }
        val live = runCatching { workbookRepository.get(project.sourceCollection, project.targetCollection) }
            .getOrNull() ?: return@withContext
        workbookRepository.closeWorkbook(live)
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
