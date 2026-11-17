package org.bibletranslationtools.otter.common.domain.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.NewTake
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.IUndoable
import org.bibletranslationtools.otter.common.data.workbook.inOrder

/**
 * Records [newTake] into [chunk]. Undo deletes it and selects [previouslySelectedTake] again; redo
 * restores and selects it. Every step is saved, the undo included.
 */
class TranslationTakeRecordAction(
    private val chunk: Chunk,
    private val newTake: NewTake,
    private val previouslySelectedTake: Take? = null
) : IUndoable {

    /** The take once [execute] has saved it. */
    var take: Take? = null
        private set

    override suspend fun execute(): SaveResult<Unit> =
        when (val result = chunk.audio.insertTake(newTake)) {
            is SaveResult.Saved -> { take = result.value; SaveResult.Saved(Unit) }
            is SaveResult.Failed -> result
            is SaveResult.Rejected -> result
        }

    override suspend fun undo(): SaveResult<Unit> {
        val saved = take ?: return SaveResult.Saved(Unit)
        return inOrder(
            { chunk.audio.deleteTake(saved) },
            { previouslySelectedTake?.let { chunk.audio.selectTake(it) } ?: SaveResult.Saved(Unit) }
        )
    }

    override suspend fun redo(): SaveResult<Unit> {
        val saved = take ?: return SaveResult.Saved(Unit)
        withContext(Dispatchers.IO) { saved.file.setLastModified(System.currentTimeMillis()) }
        return inOrder({ chunk.audio.restoreTake(saved) }, { chunk.audio.selectTake(saved) })
    }
}

/** Deletes [take] from [chunk]. Undo restores it, and selects it again if it was selected. */
class TranslationTakeDeleteAction(
    private val chunk: Chunk,
    private val take: Take,
    private val isTakeSelected: Boolean,
    private val postDeleteCallback: suspend (Take, Boolean) -> Unit
) : IUndoable {

    override suspend fun execute(): SaveResult<Unit> {
        val result = chunk.audio.deleteTake(take)
        if (result is SaveResult.Saved) postDeleteCallback(take, isTakeSelected)
        return result
    }

    override suspend fun undo(): SaveResult<Unit> {
        withContext(Dispatchers.IO) { take.file.setLastModified(System.currentTimeMillis()) }
        return inOrder(
            { chunk.audio.restoreTake(take) },
            { if (isTakeSelected) chunk.audio.selectTake(take) else SaveResult.Saved(Unit) }
        )
    }

    override suspend fun redo() = execute()
}

/** Selects [take] in [chunk]. Undo selects [previouslySelectedTake] again, or clears the selection. */
class TranslationTakeSelectAction(
    private val chunk: Chunk,
    private val take: Take,
    private val previouslySelectedTake: Take? = null
) : IUndoable {
    override suspend fun execute(): SaveResult<Unit> {
        withContext(Dispatchers.IO) { take.file.setLastModified(System.currentTimeMillis()) }
        return chunk.audio.selectTake(take)
    }

    override suspend fun undo(): SaveResult<Unit> = chunk.audio.selectTake(previouslySelectedTake)

    override suspend fun redo() = execute()
}

/** Gives [take] the checking status [checking], for its audio as it is now. Undo puts [oldCheckingStage] back. */
class TranslationTakeApproveAction(
    private val audio: AssociatedAudio,
    private val take: Take,
    private val checking: CheckingStatus,
    private val oldCheckingStage: TakeCheckingState
) : IUndoable {

    private var undoCallback: suspend () -> Unit = {}
    private var redoCallback: suspend () -> Unit = {}

    override suspend fun execute(): SaveResult<Unit> {
        val checksum = withContext(Dispatchers.IO) { take.checksum() }
        return unit(audio.setChecking(take, TakeCheckingState(checking, checksum)))
    }

    override suspend fun undo(): SaveResult<Unit> {
        val result = unit(audio.setChecking(take, oldCheckingStage))
        if (result is SaveResult.Saved) undoCallback()
        return result
    }

    override suspend fun redo(): SaveResult<Unit> {
        val result = execute()
        if (result is SaveResult.Saved) redoCallback()
        return result
    }

    fun setUndoCallback(op: suspend () -> Unit) {
        undoCallback = op
    }

    fun setRedoCallback(op: suspend () -> Unit) {
        redoCallback = op
    }
}

internal fun unit(result: SaveResult<*>): SaveResult<Unit> = when (result) {
    is SaveResult.Saved -> SaveResult.Saved(Unit)
    is SaveResult.Failed -> result
    is SaveResult.Rejected -> result
}
