package org.bibletranslationtools.otter.common.data.workbook

/**
 * An action that can be undone and redone. Each step returns a [SaveResult]: an action that changes
 * the workbook saves the change before it counts, and the undo history keeps a step that failed
 * where it was, so it can be tried again.
 */
interface IUndoable {
    suspend fun execute(): SaveResult<Unit>
    suspend fun undo(): SaveResult<Unit>
    suspend fun redo(): SaveResult<Unit>
}

/** Saved, for an action that changes nothing that is stored. */
val NOTHING_TO_SAVE: SaveResult<Unit> = SaveResult.Saved(Unit)

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
    return NOTHING_TO_SAVE
}
