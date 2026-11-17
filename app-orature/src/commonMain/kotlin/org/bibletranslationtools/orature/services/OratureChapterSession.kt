package org.bibletranslationtools.orature.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.domain.IUndoable
import org.bibletranslationtools.otter.common.domain.content.RechunkAction
import org.bibletranslationtools.otter.common.domain.model.UndoableActionHistory
import org.slf4j.LoggerFactory

/**
 * The chapter the user is working in, and its undo history.
 *
 * Every step of a chapter (chunking, blind draft, peer edit, the checks, final review) adds its
 * actions to this one history, and the page header's undo and redo drive it. So a rechunk stays
 * undoable while the user moves between the chapter's steps.
 *
 * [leave] ends the history and makes a rechunk stick: the chapter's inactive chunk layouts and their
 * takes are deleted. Call it from every way out of a chapter: changing chapter, leaving the book,
 * switching project, opening narration on the chapter, and closing the app. Startup settles anything
 * a crash left behind, since this history does not survive a restart.
 */
class OratureChapterSession {

    private val logger = LoggerFactory.getLogger(OratureChapterSession::class.java)
    private val lock = Mutex()
    private val history = UndoableActionHistory<IUndoable>()

    private var chapter: Chapter? = null

    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** The chapter in session, if any. */
    val current: Chapter? get() = chapter

    /** Starts a session for [next], leaving the current chapter first if it is a different one. */
    suspend fun enter(next: Chapter) {
        lock.withLock {
            if (chapter == next) return
            leaveLocked()
            chapter = next
        }
    }

    /**
     * Ends the session: the history goes, and the chapter's rechunk, if any, sticks. Safe to call
     * more than once.
     */
    suspend fun leave(): SaveResult<Unit> = lock.withLock { leaveLocked() }

    /**
     * [leave], but only while [leaving] is the chapter in session. For a screen that closes after
     * the fact (its ViewModel is cleared later, from an app-lifetime scope): another screen may have
     * entered a chapter since, and its session must not end.
     */
    suspend fun leave(leaving: Chapter): SaveResult<Unit> = lock.withLock {
        if (chapter == leaving) leaveLocked() else SaveResult.Saved(Unit)
    }

    private suspend fun leaveLocked(): SaveResult<Unit> {
        val leaving = chapter ?: return SaveResult.Saved(Unit)
        chapter = null
        history.clear()
        publish()
        return leaving.settleDrafts().also {
            if (it !is SaveResult.Saved) logger.error("Could not settle chapter ${leaving.sort}'s chunk layouts: $it")
        }
    }

    suspend fun execute(action: IUndoable): SaveResult<Unit> = lock.withLock { history.execute(action).also { publish() } }

    suspend fun undo(): SaveResult<Unit> = lock.withLock { history.undo().also { publish() } }

    suspend fun redo(): SaveResult<Unit> = lock.withLock { history.redo().also { publish() } }

    /**
     * The recordings an undo would move into a layout that is deleted when the user leaves the
     * chapter: non-zero only when the next undo would undo a rechunk. Ask before undoing.
     */
    fun recordingsAtRiskOnUndo(): Int = (history.peekUndo() as? RechunkAction)?.recordingsAtRisk() ?: 0

    private fun publish() {
        _canUndo.value = history.canUndo()
        _canRedo.value = history.canRedo()
    }
}
