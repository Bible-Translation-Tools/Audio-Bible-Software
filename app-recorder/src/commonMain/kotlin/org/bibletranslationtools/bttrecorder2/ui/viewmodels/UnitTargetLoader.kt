package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.content.Recordable

/**
 * One thing the user can record or play back: a whole chapter, or a single chunk within it.
 *
 * "Unit" is the recorder's own word for this (see `UnitListViewModel`, and the `unitNumber`
 * navigation argument). [RecorderViewModel] and [PlaybackViewModel] each used to declare their own
 * private, structurally identical copy of this type.
 */
data class UnitTarget(
    val chapter: Chapter,
    val chunk: Chunk?
) {
    val recordable: Recordable
        get() = chunk ?: chapter
}

/**
 * Turns the navigation arguments a screen is opened with — source/target collection ids plus a
 * chapter and optional unit — into the flat, ordered list of [UnitTarget]s that screen can page
 * through, and the index of the one that was asked for.
 *
 * This lived twice, near-identically, in [RecorderViewModel.loadTarget] and
 * [PlaybackViewModel.loadTarget]: find the workbook whose source and target collection ids match,
 * order the chapters, then expand each into a chapter-level target followed by its chunks in order.
 *
 * It stays in :app-recorder rather than moving to :shared because the flattened chapter-then-chunks
 * list is this app's navigation model — Orature paginates by translation step, not by unit. It is a
 * separate class rather than a ViewModel method so it can be tested without a ViewModel.
 *
 * Callers are responsible for their own dispatching; both call it from `Dispatchers.IO`.
 */
class UnitTargetLoader(
    private val workbookRepository: IWorkbookRepository
) {

    /**
     * @param requestedIndex index into [targets] of the requested chapter/unit, or -1 when the
     *   request matched nothing. Left to the caller rather than defaulted here because the two
     *   screens want different fallbacks: playback opens the first target, while the recorder
     *   opens the first *chunk* so that entering from the project-list mic lands on a recordable
     *   unit instead of the chapter-meta row.
     */
    data class LoadedTargets(
        val workbook: Workbook,
        val targets: List<UnitTarget>,
        val requestedIndex: Int
    )

    /**
     * @param unitNumber the chunk sort to open, or null for the chapter-level target
     * @return null when no workbook matches the ids, or the target book has no chapters — in
     *   either case there is nothing for the screen to show and it should leave its state alone.
     */
    suspend fun load(
        sourceId: Int,
        targetId: Int,
        chapterNumber: Int,
        unitNumber: Int?
    ): LoadedTargets? {
        val workbook = workbookRepository.getProjectsSuspend().find {
            it.source.collectionId == sourceId && it.target.collectionId == targetId
        } ?: return null

        val chapters = workbook.target.chapters.toList().await().sortedBy { it.sort }
        if (chapters.isEmpty()) return null

        val targets = mutableListOf<UnitTarget>()
        chapters.forEach { chapter ->
            targets.add(UnitTarget(chapter = chapter, chunk = null))
            chapter.chunksSuspend().sortedBy { it.sort }.forEach { chunk ->
                targets.add(UnitTarget(chapter = chapter, chunk = chunk))
            }
        }

        val requestedIndex = targets.indexOfFirst { target ->
            if (target.chapter.sort != chapterNumber) return@indexOfFirst false
            if (unitNumber == null) target.chunk == null else target.chunk?.sort == unitNumber
        }

        return LoadedTargets(workbook, targets, requestedIndex)
    }
}
