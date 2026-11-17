package org.bibletranslationtools.otter.common.data.workbook

import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import java.time.LocalDate

/**
 * What the workbook needs from storage: the one port between the workbook's nodes and the database
 * and project files. The nodes call it from their own methods, so saving stays automatic for the code
 * that uses the workbook, and the node changes its in-memory state only after a call here returns.
 *
 * Every write is one transaction. A write that fails throws, and the node turns that into a
 * [SaveResult.Failed].
 *
 * Implemented in `persistence/` over SQLite and the project's files, and by a fake for tests.
 */
interface WorkbookStore {

    /** The takes of [owner], deleted ones included, and which one is selected. */
    suspend fun loadAudio(owner: ElementId): AudioState

    /** The rows of [chapter]'s current chunk layout, each with its takes. */
    suspend fun loadChunks(chapter: ChapterId): ChapterLayout

    /** Saves [take] as [owner]'s, and selects it in the same transaction when [select] is set. */
    suspend fun addTake(owner: ElementId, take: Take, select: Boolean): Take

    /** Sets [owner]'s selected take; null clears it. */
    suspend fun setSelected(owner: ElementId, take: TakeId?)

    /**
     * Marks [take] deleted [on] that date, and clears [clearSelectionOf]'s selection in the same
     * transaction when the take was selected there.
     */
    suspend fun markDeleted(take: TakeId, on: LocalDate, clearSelectionOf: ElementId?)

    /** Undoes [markDeleted]. */
    suspend fun restore(take: TakeId)

    /** Sets [take]'s checking status and the checksum it was given for. */
    suspend fun setChecking(take: TakeId, checking: TakeCheckingState)

    /**
     * Adds [rows] as a new chunk layout (draft) of [chapter], linked to their source rows, and makes it
     * the active one, in one transaction. The previous layout's rows and takes are kept, inactive, so
     * the rechunk can be undone with [switchDraft]. Returns the new draft.
     */
    suspend fun rechunk(chapter: ChapterId, rows: List<Content>): DraftId

    /** Makes [to] the active chunk layout of [chapter]; nothing is deleted. */
    suspend fun switchDraft(chapter: ChapterId, to: DraftId)

    /**
     * Deletes every chunk layout of [chapter] except the active one: their rows and their takes' rows
     * in one transaction, then the takes' audio files.
     */
    suspend fun deleteInactiveDrafts(chapter: ChapterId)

    /** Chapters that may have inactive chunk layouts, for the clean-up at startup. */
    suspend fun chaptersWithDrafts(): List<ChapterId>
}

/** The layouts before and after a rechunk: what undo and redo switch between. */
data class RechunkReceipt(val previous: DraftId, val next: DraftId)

/** An element's takes and its selection, as one value: the two must always agree. */
data class AudioState(
    val takes: List<Take> = emptyList(),
    val selected: TakeId? = null
) {
    /** The selected take, if it is in [takes]. */
    val selectedTake: Take? get() = selected?.let { id -> takes.firstOrNull { it.id == id } }

    companion object {
        val EMPTY = AudioState()
    }
}

/** One chunk layout of a chapter, as storage holds it. */
data class ChapterLayout(
    val draft: DraftId,
    val chunks: List<ChunkRecord>
)

/** A chunk's row and its takes, as storage holds them; the workbook builds a [Chunk] from it. */
data class ChunkRecord(
    val id: ChunkId,
    val sort: Int,
    val label: String,
    val start: Int,
    val end: Int,
    val bridged: Boolean,
    val contentType: ContentType,
    val textItem: TextItem,
    val draft: DraftId,
    val audio: AudioState
)
