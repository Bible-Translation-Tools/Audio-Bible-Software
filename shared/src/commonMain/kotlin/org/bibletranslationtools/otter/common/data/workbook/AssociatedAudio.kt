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
class AssociatedAudio(
    val owner: ElementId,
    initial: AudioState,
    private val store: WorkbookStore,
    private val writer: WorkbookWriter
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

    /** Saves [take] and selects it in the same transaction. Returns the saved take, which has its id. */
    suspend fun insertTake(take: Take): SaveResult<Take> = writer.save {
        val saved = store.addTake(owner, take)
        _state.update { s -> s.copy(takes = s.takes + saved, selected = saved.id) }
        saved
    }

    /** Selects [take]; null clears the selection. */
    suspend fun selectTake(take: Take?): SaveResult<Unit> = writer.save(savedGate(take)) {
        store.setSelected(owner, take?.id)
        _state.update { it.copy(selected = take?.id) }
    }

    /** Marks [take] deleted. If it was selected, the selection is cleared in the same step. */
    suspend fun deleteTake(take: Take): SaveResult<Unit> = writer.save(savedGate(take)) {
        val id = take.id!!
        val today = LocalDate.now()
        val wasSelected = _state.value.selected == id
        store.markDeleted(id, today, clearSelectionOf = owner.takeIf { wasSelected })
        _state.update { s ->
            s.copy(
                takes = s.takes.map { if (it.id == take.id) it.copy(deletedOn = today) else it },
                selected = s.selected.takeUnless { it == take.id }
            )
        }
    }

    /** Undoes [deleteTake]. Does not select the take again; call [selectTake] for that. */
    suspend fun restoreTake(take: Take): SaveResult<Unit> = writer.save(savedGate(take)) {
        store.restore(take.id!!)
        _state.update { s -> s.copy(takes = s.takes.map { if (it.id == take.id) it.copy(deletedOn = null) else it }) }
    }

    /**
     * Sets [take]'s checking status. Returns the updated take: the one this element holds, so a
     * caller's older copy (from before a delete, say) does not bring back stale fields.
     */
    suspend fun setChecking(take: Take, checking: TakeCheckingState): SaveResult<Take> = writer.save(savedGate(take)) {
        val id = take.id!!
        store.setChecking(id, checking)
        val updated = (take(id) ?: take).copy(checking = checking)
        _state.update { s -> s.copy(takes = s.takes.map { if (it.id == take.id) updated else it }) }
        updated
    }

    /** The take with [id] as this element holds it now, or null. */
    fun take(id: TakeId?): Take? = id?.let { _state.value.takes.firstOrNull { take -> take.id == it } }

    /** Refuses a take that was never saved: it has no id to change. */
    private fun savedGate(take: Take?): () -> RejectReason? =
        { if (take != null && take.id == null) RejectReason.UNSAVED else null }
}
