package org.bibletranslationtools.otter.common.data.workbook

import kotlinx.coroutines.CancellationException
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
 * The lock is not reentrant: a change must not call another node's change from inside [save]. Rules
 * that span nodes are one store call.
 */
class WorkbookWriter {

    private val logger = LoggerFactory.getLogger(WorkbookWriter::class.java)
    private val lock = Mutex()
    private var closed = false

    /**
     * Runs [block] under the workbook's lock. [gate] runs inside the lock first; a reason it returns
     * refuses the change without running [block].
     */
    suspend fun <T> save(gate: () -> RejectReason? = { null }, block: suspend () -> T): SaveResult<T> =
        lock.withLock {
            if (closed) return@withLock SaveResult.Rejected(RejectReason.CLOSED)
            gate()?.let { return@withLock SaveResult.Rejected(it) }
            try {
                SaveResult.Saved(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("A workbook change was not saved", e)
                SaveResult.Failed(SaveFailure.of(e), e)
            }
        }

    /** Refuses later changes and waits for the one in progress to finish. */
    suspend fun close() {
        lock.withLock { closed = true }
    }

    val isClosed: Boolean get() = closed
}
