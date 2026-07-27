package org.bibletranslationtools.orature.ui.viewmodels

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.collections.DeleteProject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * App-scoped project-deletion coordinator (JVM: DeleteProject + ProjectWizardViewModel's
 * projectDeleteCounter). Wraps the shared [DeleteProject] use-case and tracks how many group deletes
 * are pending (during their undo window), so project creation can wait for them to finish first —
 * avoiding a concurrent get + delete on the same DB rows. Registered as a Koin single.
 */
class OratureProjectDeletion : KoinComponent {

    private val deleteProject: DeleteProject by inject()

    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending

    /** Enter/leave the "delete pending" window (JVM: increase/decreaseProjectDeleteCounter). */
    fun beginPending() { _pending.value += 1 }
    fun endPending() { _pending.value = (_pending.value - 1).coerceAtLeast(0) }

    /** Reset a single book to its initial state (JVM: deleteBook → DeleteProject.delete). */
    suspend fun deleteBook(descriptor: WorkbookDescriptor) {
        deleteProject.delete(descriptor).await()
    }

    /** Delete a whole project group and remove its descriptors (JVM: deleteProjects). */
    suspend fun deleteGroup(descriptors: List<WorkbookDescriptor>) {
        deleteProject.deleteProjects(descriptors).await()
    }

    /** Suspend until no group delete is pending (JVM: waitForProjectDeletionFinishes). */
    suspend fun awaitClear() {
        _pending.first { it == 0 }
    }
}
