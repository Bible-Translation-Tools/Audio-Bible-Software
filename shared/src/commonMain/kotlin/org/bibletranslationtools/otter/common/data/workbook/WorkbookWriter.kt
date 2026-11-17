package org.bibletranslationtools.otter.common.data.workbook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * The one writer of a workbook: every node of one workbook saves through the same instance.
 *
 * [save] runs a change while holding the workbook's lock, so changes run one at a time in the order
 * they were called: the store call and the in-memory update after it, together. A failure becomes a
 * [SaveResult.Failed] instead of an exception; cancellation still propagates.
 *
 * It also keeps the workbook's [status], so failures that no screen is waiting on (settling drafts
 * when leaving a chapter, closing the workbook) still reach the user.
 *
 * The lock is not reentrant: a change must not call another node's change from inside [save]. Rules
 * that span nodes are one store call.
 */
class WorkbookWriter {

    private val logger = LoggerFactory.getLogger(WorkbookWriter::class.java)
    private val lock = Mutex()
    private val waiting = MutableStateFlow(0)
    private val _status = MutableStateFlow<SaveStatus>(SaveStatus.Saved)
    private var closed = false

    /** Whether the workbook's changes are saved, being saved, or not saved. */
    val status: StateFlow<SaveStatus> = _status.asStateFlow()

    /**
     * Runs [block] under the workbook's lock. [gate] runs inside the lock first; a reason it returns
     * refuses the change without running [block].
     */
    suspend fun <T> save(gate: () -> RejectReason? = { null }, block: suspend () -> T): SaveResult<T> {
        _status.value = SaveStatus.Saving(waiting.updateAndGet { it + 1 })
        try {
            return lock.withLock {
                if (closed) return@withLock SaveResult.Rejected(RejectReason.CLOSED)
                gate()?.let { return@withLock SaveResult.Rejected(it) }
                try {
                    SaveResult.Saved(block()).also { _status.value = settled() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("A workbook change was not saved", e)
                    SaveResult.Failed(SaveFailure.of(e), e).also {
                        _status.value = SaveStatus.NotSaved(it.failure, e)
                    }
                }
            }
        } finally {
            val left = waiting.updateAndGet { it - 1 }
            val now = _status.value
            if (now is SaveStatus.Saving) _status.value = if (left > 0) SaveStatus.Saving(left) else SaveStatus.Saved
        }
    }

    /** Refuses later changes and waits for the one in progress to finish. */
    suspend fun close() {
        lock.withLock { closed = true }
    }

    val isClosed: Boolean get() = closed

    private fun settled(): SaveStatus = waiting.value.let { if (it > 1) SaveStatus.Saving(it - 1) else SaveStatus.Saved }
}

/** Whether a workbook's changes have reached storage. */
sealed interface SaveStatus {
    data object Saved : SaveStatus
    data class Saving(val count: Int) : SaveStatus

    /** The last change failed; it stays shown until a later change saves. */
    data class NotSaved(val failure: SaveFailure, val cause: Throwable) : SaveStatus
}
