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
 * The one queue every database write in the app goes through.
 *
 * SQLDelight transactions are bound to the thread that began them, on both the JDBC (desktop) and
 * Android drivers, and the runtime throws if a transaction ends on another thread. Before this
 * class, writers ran wherever they happened to be — Rx `Schedulers.io()`, the caller's thread,
 * `Schedulers.single()` in project deletion — each on its own connection, and could collide.
 *
 * [write] runs its block as ONE transaction on a dispatcher that runs one task at a time, so:
 *  - a transaction starts and ends on the same thread (the block is plain blocking code);
 *  - no two writes overlap, app-wide;
 *  - writes run in the order they were submitted.
 *
 * [scheduler] is the same queue as an Rx [Scheduler], so repositories that have not moved to
 * coroutines yet share it (`subscribeOn(databaseWriter.scheduler)`).
 *
 * One rule: never wait on the queue from inside a write. The queue is busy running the waiting
 * block, so the inner write could never start. [write] checks for that and fails fast with
 * [ReentrantWriteException] instead of hanging.
 *
 * Bind it as a singleton: two instances would be two queues.
 */
class DatabaseWriter(
    private val database: DaoProvider,
    dispatcher: CoroutineDispatcher = defaultDispatcher()
) {
    private val queue: CoroutineDispatcher = dispatcher

    /**
     * The queue as an Rx scheduler, for code that has not moved to coroutines yet. Its tasks are
     * marked as running on the queue: an Rx write delivers its result on the queue's thread, inside
     * the same task, so whatever runs downstream of it synchronously holds the queue too (see
     * [writeBlocking]).
     */
    val scheduler: Scheduler = Schedulers.from { task ->
        val marked = Runnable { inQueueTask { task.run() } }
        // A dispatcher that runs in place (tests' Unconfined) must not be dispatched to.
        if (queue.isDispatchNeeded(EmptyCoroutineContext)) queue.dispatch(EmptyCoroutineContext, marked) else marked.run()
    }

    /** Runs [block] as one transaction on the queue and returns its result. */
    suspend fun <T> write(block: () -> T): T {
        checkNotOnQueue()
        return withContext(queue) { runOnQueue(block) }
    }

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

    private fun checkNotOnQueue() {
        if (isOnQueue()) throw ReentrantWriteException()
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

/**
 * Code running inside a [DatabaseWriter] block waited on the queue. Call the DAOs directly
 * instead: the block is already one transaction.
 */
class ReentrantWriteException : IllegalStateException(
    "Waited on the database write queue from inside a write; the queue would never get to it. " +
        "Call the DAOs directly inside the running block instead."
)
