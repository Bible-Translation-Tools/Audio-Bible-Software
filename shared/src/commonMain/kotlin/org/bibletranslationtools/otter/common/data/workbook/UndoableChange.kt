package org.bibletranslationtools.otter.common.data.workbook

import java.util.ArrayDeque
import java.util.Deque

/**
 * A change to the workbook that can be undone and redone. Each step saves before it counts and
 * returns its [SaveResult].
 */
interface UndoableChange {
    suspend fun execute(): SaveResult<Unit>
    suspend fun undo(): SaveResult<Unit>
    suspend fun redo(): SaveResult<Unit>
}

/**
 * Runs [steps] in order and stops at the first that is not saved, returning its result; returns
 * saved when every step is.
 */
suspend fun inOrder(vararg steps: suspend () -> SaveResult<*>): SaveResult<Unit> {
    for (step in steps) {
        when (val result = step()) {
            is SaveResult.Saved -> Unit
            is SaveResult.Failed -> return result
            is SaveResult.Rejected -> return result
        }
    }
    return SaveResult.Saved(Unit)
}

/**
 * The undo and redo history of [UndoableChange]s. A change moves between the stacks only when its
 * step was saved, so an undo that failed to save stays undoable instead of vanishing.
 */
class UndoableChangeHistory {
    private val undoStack: Deque<UndoableChange> = ArrayDeque()
    private val redoStack: Deque<UndoableChange> = ArrayDeque()

    suspend fun execute(change: UndoableChange): SaveResult<Unit> {
        val result = change.execute()
        if (result is SaveResult.Saved) {
            undoStack.push(change)
            redoStack.clear()
        }
        return result
    }

    suspend fun undo(): SaveResult<Unit> {
        val change = undoStack.peek() ?: return SaveResult.Saved(Unit)
        val result = change.undo()
        if (result is SaveResult.Saved) {
            undoStack.pop()
            redoStack.push(change)
        }
        return result
    }

    suspend fun redo(): SaveResult<Unit> {
        val change = redoStack.peek() ?: return SaveResult.Saved(Unit)
        val result = change.redo()
        if (result is SaveResult.Saved) {
            redoStack.pop()
            undoStack.push(change)
        }
        return result
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    /** The change the next [undo] would undo, if any. */
    fun peekUndo(): UndoableChange? = undoStack.peek()

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
}
