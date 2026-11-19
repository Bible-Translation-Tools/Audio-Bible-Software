package org.bibletranslationtools.otter.common.domain.model

import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.domain.IUndoable
import org.bibletranslationtools.otter.common.domain.NOTHING_TO_SAVE
import java.util.ArrayDeque
import java.util.Deque

/**
 * An undo and redo history. An action moves between the stacks only when its step was saved, so
 * an undo that failed to save stays undoable instead of vanishing from the history.
 */
class UndoableActionHistory<T : IUndoable> {
    private val undoStack: Deque<IUndoable> = ArrayDeque()
    private val redoStack: Deque<IUndoable> = ArrayDeque()

    suspend fun execute(action: T): SaveResult<Unit> {
        val result = action.execute()
        if (result is SaveResult.Saved) {
            undoStack.push(action)
            redoStack.clear()
        }
        return result
    }

    suspend fun undo(): SaveResult<Unit> {
        val op = undoStack.peek() ?: return NOTHING_TO_SAVE
        val result = op.undo()
        if (result is SaveResult.Saved) {
            undoStack.pop()
            redoStack.push(op)
        }
        return result
    }

    suspend fun redo(): SaveResult<Unit> {
        val action = redoStack.peek() ?: return NOTHING_TO_SAVE
        val result = action.redo()
        if (result is SaveResult.Saved) {
            redoStack.pop()
            undoStack.push(action)
        }
        return result
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    /** The action the next [undo] would undo, if any. */
    fun peekUndo(): IUndoable? = undoStack.peek()

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
}
