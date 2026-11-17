package org.bibletranslationtools.bttrecorder2.services

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor

/**
 * The project the user has open, and its workbook.
 *
 * The recorder's screens open a project's workbook here rather than from the repository, so one
 * place knows which workbook is open and closes it when the user leaves the project: back to the
 * project list or the main menu, into another project, or deleting it. Closing waits for the change
 * in progress to be saved and refuses later ones; the next opening reads the project fresh.
 *
 * A Koin `single`: every screen of the open project shares it.
 */
class ProjectSession(
    private val workbookRepository: IWorkbookRepository,
    private val collectionRepository: ICollectionRepository
) {
    private val lock = Mutex()
    private var open: Workbook? = null

    /**
     * The workbook of the project whose source and target books are [sourceId] and [targetId], now
     * the open project. Opening another project closes the one that was open.
     *
     * @return null when either book no longer exists
     */
    suspend fun open(sourceId: Int, targetId: Int): Workbook? = lock.withLock {
        open?.takeIf { !it.isClosed && it.source.collectionId == sourceId && it.target.collectionId == targetId }
            ?.let { return@withLock it }

        val source = collectionRepository.getProjectSuspend(sourceId) ?: return@withLock null
        val target = collectionRepository.getProjectSuspend(targetId) ?: return@withLock null
        val workbook = workbookRepository.get(source, target)
        closeOpen(keep = workbook)
        open = workbook
        workbook
    }

    /** Closes the open project's workbook: the user went back to the project list or the main menu. */
    suspend fun leave() = lock.withLock { closeOpen(keep = null) }

    /**
     * Closes the open workbook if it is the project book [targetId]'s, before that book moves to
     * another source edition: the workbook is keyed by its source, which is about to change.
     */
    suspend fun closeBook(targetId: Int) = lock.withLock {
        if (open?.target?.collectionId == targetId) closeOpen(keep = null)
    }

    /**
     * Closes [project]'s workbook before its files are deleted, so nothing writes to the project
     * while it goes: the open one, and any copy another screen opened (an export).
     */
    suspend fun closeForDeletion(project: WorkbookDescriptor) = lock.withLock {
        val isOpen = open?.let {
            it.source.collectionId == project.sourceCollection.id &&
                it.target.collectionId == project.targetCollection.id
        } == true
        if (isOpen) closeOpen(keep = null)
        // The repository's live copy, if another screen opened one. A project whose books cannot
        // be built has no workbook to close, and must still be deletable.
        val live = runCatching { workbookRepository.get(project.sourceCollection, project.targetCollection) }
            .getOrNull() ?: return@withLock
        withContext(NonCancellable) { workbookRepository.closeWorkbook(live) }
    }

    /**
     * Closes the open workbook unless it is [keep]. A workbook equal to [keep] but not the same
     * object was already closed (the repository keeps one live copy per project), and closing it
     * again would drop [keep] from the repository's open workbooks.
     *
     * Not cancellable: a screen that leaves mid-close must not leave the workbook half closed.
     */
    private suspend fun closeOpen(keep: Workbook?) {
        val previous = open ?: return
        open = null
        if (previous == keep || previous.isClosed) return
        withContext(NonCancellable) { workbookRepository.closeWorkbook(previous) }
    }
}
