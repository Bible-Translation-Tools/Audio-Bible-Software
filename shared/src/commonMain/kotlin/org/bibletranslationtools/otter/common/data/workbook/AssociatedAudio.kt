package org.bibletranslationtools.otter.common.data.workbook

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * The takes of one element (a chapter, a chunk, a resource's title or body) and which one is
 * selected.
 *
 * Its state is one [AudioState] value, so the takes and the selection always change together: no
 * reader ever sees a take deleted but still selected.
 *
 * Every change saves itself: the method saves through the [WorkbookStore] first, under the
 * workbook's [WorkbookWriter], and only when that succeeds does it change the state. It returns a
 * [SaveResult], so the caller learns whether the change was saved; on failure memory is untouched.
 */
class AssociatedAudio internal constructor(
    val owner: ElementId,
    initial: AudioState,
    private val store: WorkbookStore,
    private val writer: WorkbookWriter,
    private val gate: () -> RejectReason? = { null }
) {
    private val _state = MutableStateFlow(initial)

    /** The takes, deleted ones included, and the selection. */
    val state: StateFlow<AudioState> = _state.asStateFlow()

    /** The takes, deleted ones included. */
    val takesFlow: Flow<List<Take>> get() = state.map { it.takes }.distinctUntilChanged()

    /** The selected take, or null. */
    val selectedFlow: Flow<Take?> get() = state.map { it.selectedTake }.distinctUntilChanged()

    fun getAllTakes(): List<Take> = _state.value.takes

    fun getSelectedTake(): Take? = _state.value.selectedTake

    /** The next take number: one past the highest, deleted takes included. */
    fun getNewTakeNumber(): Int = (_state.value.takes.maxOfOrNull { it.number } ?: 0) + 1

    /**
     * Saves [take] and, when [select] is set, selects it in the same transaction. Returns the saved
     * take, which already has its id.
     */
    suspend fun insertTake(take: NewTake, select: Boolean = true): SaveResult<Take> = writer.save(gate) {
        val saved = store.addTake(owner, take, select)
        _state.update { s -> s.copy(takes = s.takes + saved, selected = if (select) saved.id else s.selected) }
        saved
    }

    /** Selects [take]; null clears the selection. */
    suspend fun selectTake(take: Take?): SaveResult<Unit> = writer.save(gate) {
        store.setSelected(owner, take?.id)
        _state.update { it.copy(selected = take?.id) }
    }

    /** Marks [take] deleted. If it was selected, the selection is cleared in the same step. */
    suspend fun deleteTake(take: Take): SaveResult<Unit> = writer.save(gate) {
        val today = LocalDate.now()
        val wasSelected = _state.value.selected == take.id
        store.markDeleted(take.id, today, clearSelectionOf = owner.takeIf { wasSelected })
        _state.update { s ->
            s.copy(
                takes = s.takes.map { if (it.id == take.id) it.copy(deletedOn = today) else it },
                selected = s.selected.takeUnless { it == take.id }
            )
        }
    }

    /** Undoes [deleteTake]. Does not select the take again; call [selectTake] for that. */
    suspend fun restoreTake(take: Take): SaveResult<Unit> = writer.save(gate) {
        store.restore(take.id)
        _state.update { s -> s.copy(takes = s.takes.map { if (it.id == take.id) it.copy(deletedOn = null) else it }) }
    }

    /** Sets [take]'s checking status. Returns the updated take. */
    suspend fun setChecking(take: Take, checking: TakeCheckingState): SaveResult<Take> = writer.save(gate) {
        store.setChecking(take.id, checking)
        val updated = take.copy(checking = checking)
        _state.update { s -> s.copy(takes = s.takes.map { if (it.id == take.id) updated else it }) }
        updated
    }

    /** The take with [id] as this element holds it now, or null. */
    fun take(id: TakeId): Take? = _state.value.takes.firstOrNull { it.id == id }
}
