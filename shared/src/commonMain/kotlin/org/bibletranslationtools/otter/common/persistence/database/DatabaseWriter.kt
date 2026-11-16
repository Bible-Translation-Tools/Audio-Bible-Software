package org.bibletranslationtools.otter.common.persistence.database

import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.Single
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.rx2.asScheduler
import kotlinx.coroutines.withContext
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
 * block, so the inner write could never start. [write], [completable] and [single] check for that
 * and fail fast with [ReentrantWriteException] instead of hanging.
 *
 * Bind it as a singleton: two instances would be two queues.
 */
class DatabaseWriter(
    private val database: DaoProvider,
    dispatcher: CoroutineDispatcher = defaultDispatcher()
) {
    private val queue: CoroutineDispatcher = dispatcher

    /** The queue as an Rx scheduler, for code that has not moved to coroutines yet. */
    val scheduler: Scheduler = queue.asScheduler()

    /** Runs [block] as one transaction on the queue and returns its result. */
    suspend fun <T> write(block: () -> T): T {
        checkNotOnQueue()
        return withContext(queue) { runOnQueue(block) }
    }

    /** [write] as a [Completable] that runs on the queue when subscribed. */
    fun completable(block: () -> Unit): Completable =
        Completable.defer {
            checkNotOnQueue()
            Completable.fromAction { runOnQueue(block) }.subscribeOn(scheduler)
        }

    /** [write] as a [Single] that runs on the queue when subscribed. */
    fun <T : Any> single(block: () -> T): Single<T> =
        Single.defer {
            checkNotOnQueue()
            Single.fromCallable { runOnQueue(block) }.subscribeOn(scheduler)
        }

    /** True while the calling thread is running a block on the queue. */
    fun isOnQueue(): Boolean = onQueue.get() == true

    private fun <T> runOnQueue(block: () -> T): T {
        onQueue.set(true)
        try {
            return database.transactionResult(block)
        } finally {
            onQueue.remove()
        }
    }

    private fun checkNotOnQueue() {
        if (isOnQueue()) throw ReentrantWriteException()
    }

    private companion object {
        /** Thread-local because a queued block is plain blocking code that runs on one thread. */
        val onQueue = ThreadLocal<Boolean>()

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
