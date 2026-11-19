/**
 * Copyright (C) 2020-2024 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.domain.translation

import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.domain.IUndoable
import org.bibletranslationtools.otter.common.domain.NOTHING_TO_SAVE
import org.bibletranslationtools.otter.common.domain.inOrder

abstract class TranslationTakeAction(
    protected val chunk: Chunk,
    protected val take: Take
) : IUndoable

class TranslationTakeRecordAction(
    chunk: Chunk,
    take: Take,
    private val previouslySelectedTake: Take? = null
) : TranslationTakeAction(chunk, take) {

    private var savedTake: Take? = null

    override suspend fun execute(): SaveResult<Unit> =
        when (val result = chunk.audio.insertTake(take)) {
            is SaveResult.Saved -> { savedTake = result.value; SaveResult.Saved(Unit) }
            is SaveResult.Failed -> result
            is SaveResult.Rejected -> result
        }

    override suspend fun undo(): SaveResult<Unit> {
        val saved = savedTake ?: return SaveResult.Saved(Unit)
        return inOrder(
            { chunk.audio.deleteTake(saved) },
            { previouslySelectedTake?.let { chunk.audio.selectTake(it) } ?: SaveResult.Saved(Unit) }
        )
    }

    override suspend fun redo(): SaveResult<Unit> {
        val saved = savedTake ?: return SaveResult.Saved(Unit)
        saved.file.setLastModified(System.currentTimeMillis())
        return inOrder({ chunk.audio.restoreTake(saved) }, { chunk.audio.selectTake(saved) })
    }
}

class TranslationTakeDeleteAction(
    chunk: Chunk,
    take: Take,
    private val postDeleteCallback: suspend (Take, Boolean) -> Unit
) : TranslationTakeAction(chunk, take) {

    private var wasSelected = false

    override suspend fun execute(): SaveResult<Unit> {
        wasSelected = chunk.audio.getSelectedTake()?.id == take.id
        val result = chunk.audio.deleteTake(take)
        if (result is SaveResult.Saved) postDeleteCallback(take, wasSelected)
        return result
    }

    override suspend fun undo(): SaveResult<Unit> {
        take.file.setLastModified(System.currentTimeMillis())
        return inOrder(
            { chunk.audio.restoreTake(take) },
            { if (wasSelected) chunk.audio.selectTake(take) else SaveResult.Saved(Unit) }
        )
    }

    override suspend fun redo() = execute()
}

class TranslationTakeSelectAction(
    chunk: Chunk,
    take: Take,
    private val previouslySelectedTake: Take? = null
) : TranslationTakeAction(chunk, take) {
    override suspend fun execute(): SaveResult<Unit> {
        take.file.setLastModified(System.currentTimeMillis())
        return chunk.audio.selectTake(take)
    }

    override suspend fun undo(): SaveResult<Unit> =
        previouslySelectedTake?.let { chunk.audio.selectTake(it) } ?: NOTHING_TO_SAVE

    override suspend fun redo() = execute()
}

class TranslationTakeApproveAction(
    private val audio: AssociatedAudio,
    private val take: Take,
    private val checking: CheckingStatus,
    private val oldCheckingStage: TakeCheckingState
) : IUndoable {

    private var undoCallback: () -> Unit = {}
    private var redoCallback: () -> Unit = {}

    override suspend fun execute(): SaveResult<Unit> {
        return inOrder({ audio.setChecking(take, TakeCheckingState(checking, take.checksum())) })
    }

    override suspend fun undo(): SaveResult<Unit> {
        val result = inOrder({ audio.setChecking(take, oldCheckingStage) })
        if (result is SaveResult.Saved) undoCallback()
        return result
    }

    override suspend fun redo(): SaveResult<Unit> {
        val result = execute()
        if (result is SaveResult.Saved) redoCallback()
        return result
    }

    fun setUndoCallback(op: () -> Unit) {
        undoCallback = op
    }

    fun setRedoCallback(op: () -> Unit) {
        redoCallback = op
    }
}
