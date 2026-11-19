package org.bibletranslationtools.otter.common.persistence.repositories

import kotlinx.coroutines.asCoroutineDispatcher
import org.bibletranslationtools.otter.common.persistence.database.DatabaseWriter
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import java.util.concurrent.Executors

/** One thread for every test's write queue, as the app has one; a daemon, so it never keeps the JVM up. */
private val testQueue = Executors.newSingleThreadExecutor { Thread(it, "test-write-queue").apply { isDaemon = true } }
    .asCoroutineDispatcher()

/** The write queue for repository tests: a single thread, like the app's. */
fun testWriter(db: DaoProvider) = DatabaseWriter(db, testQueue)
