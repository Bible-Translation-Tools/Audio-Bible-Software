package org.bibletranslationtools.otter.common.persistence.database

import io.reactivex.Scheduler
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider

/**
 * The one queue every database write in the app goes through. SQLDelight transactions are bound to
 * the thread that began them, so [write] runs its block as one transaction on a dispatcher that runs
 * one task at a time: writes never overlap and run in the order they were submitted. [scheduler] is
 * the same queue for Rx code.
 *
 * Never wait on the queue from inside a write: the queue is busy running the waiting block.
 *
 * Bind it as a singleton: two instances would be two queues.
 */
class DatabaseWriter(
    private val database: DaoProvider,
    private val queue: CoroutineDispatcher = defaultDispatcher()
) {

    /**
     * The queue as an Rx scheduler, for code that has not moved to coroutines yet. Its tasks are
     * marked as running on the queue: an Rx write delivers its result on the queue's thread, inside
     * the same task, so whatever runs downstream of it synchronously holds the queue too (see
     * [writeBlocking]).
     */
    val scheduler: Scheduler = Schedulers.from { task ->
        queue.dispatch(EmptyCoroutineContext, Runnable { inQueueTask { task.run() } })
    }

    /** Runs [block] as one transaction on the queue and returns its result. */
    suspend fun <T> write(block: () -> T): T = withContext(queue) { runOnQueue(block) }

    /**
     * [write] for blocking code. Called from code already holding the queue, it runs [block] there
     * and then instead of waiting for a turn it would never get: inside the running transaction when
     * called from a write block, or as its own transaction when called downstream of an Rx write on
     * the queue's thread.
     */
    fun <T> writeBlocking(block: () -> T): T =
        if (isOnQueue() || inQueueTask.get() == true) {
            database.transactionResult(block)
        } else {
            kotlinx.coroutines.runBlocking { write(block) }
        }

    /** True while the calling thread is running a write block on the queue. */
    private fun isOnQueue(): Boolean = onQueue.get() == true

    private fun <T> runOnQueue(block: () -> T): T {
        val outer = onQueue.get()
        onQueue.set(true)
        try {
            return database.transactionResult(block)
        } finally {
            if (outer == null) onQueue.remove() else onQueue.set(outer)
        }
    }

    private inline fun inQueueTask(task: () -> Unit) {
        val outer = inQueueTask.get()
        inQueueTask.set(true)
        try {
            task()
        } finally {
            if (outer == null) inQueueTask.remove() else inQueueTask.set(outer)
        }
    }

    private companion object {
        /** Thread-local because a queued block is plain blocking code that runs on one thread. */
        val onQueue = ThreadLocal<Boolean>()

        /** Set while an Rx task scheduled on the queue runs; it holds the queue until it returns. */
        val inQueueTask = ThreadLocal<Boolean>()

        @OptIn(ExperimentalCoroutinesApi::class)
        fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}
