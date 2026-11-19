package org.bibletranslationtools.otter.common.domain.content

import org.bibletranslationtools.otter.common.audio.AudioCue
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.RechunkReceipt
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.IUndoable
import java.io.File

/**
 * A rechunk, as an entry in the chapter's undo history. Undo makes the previous layout active again
 * with its takes; redo makes the new one active. Neither deletes anything: the layout that is not
 * active is deleted when the rechunk sticks, which is when the user leaves the chapter.
 */
class RechunkAction(
    private val rechunk: RechunkChapter,
    private val workbook: Workbook,
    private val chapter: Chapter,
    private val cues: List<AudioCue>,
    private val sourceAudio: File
) : IUndoable {

    /** The two layouts, once [execute] has saved the rechunk. */
    var receipt: RechunkReceipt? = null
        private set

    override suspend fun execute(): SaveResult<Unit> =
        when (val result = rechunk.rechunk(workbook, chapter, cues, sourceAudio)) {
            is SaveResult.Saved -> { receipt = result.value; SaveResult.Saved(Unit) }
            is SaveResult.Failed -> result
            is SaveResult.Rejected -> result
        }

    override suspend fun undo(): SaveResult<Unit> =
        receipt?.let { rechunk.switchDraft(workbook, chapter, it.previous, sourceAudio) } ?: SaveResult.Saved(Unit)

    override suspend fun redo(): SaveResult<Unit> =
        receipt?.let { rechunk.switchDraft(workbook, chapter, it.next, sourceAudio) } ?: SaveResult.Saved(Unit)

    /**
     * How many recordings were made into the new layout since the rechunk. Undoing moves them into
     * an inactive layout, and leaving the chapter then deletes them, so the screen should warn first.
     */
    fun recordingsAtRisk(): Int {
        val next = receipt?.next ?: return 0
        val state = chapter.state.value
        if (state.activeDraft != next) return 0
        return state.chunks.sumOf { chunk -> chunk.audio.getAllTakes().count { !it.isDeleted() } }
    }
}
