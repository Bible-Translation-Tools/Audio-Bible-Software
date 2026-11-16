package org.bibletranslationtools.otter.common.persistence.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import java.io.File
import java.util.Collections
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DatabaseWriter] against a real file database with foreign keys on, the way desktop opens it:
 * the JDBC driver's threaded connection manager, where a transaction is bound to one thread.
 */
class DatabaseWriterTest {

    private val dbFile = File.createTempFile("writer-", ".sqlite").apply { delete() }
    private val driver = JdbcSqliteDriver(
        "jdbc:sqlite:${dbFile.absolutePath}",
        Properties().apply { put("foreign_keys", "true") }
    )
    private val db = SqlDelightAppDatabase.createFresh(driver)
    private val writer = DatabaseWriter(db)

    @AfterTest
    fun cleanup() {
        driver.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        File("${dbFile.absolutePath}-journal").delete()
    }

    private fun language(slug: String) = LanguageEntity(
        id = 0, slug = slug, name = slug, anglicizedName = slug, direction = "ltr", gateway = 0, region = ""
    )

    @Test
    fun `concurrent writes all commit and never overlap`() = runBlocking {
        val running = AtomicInteger(0)
        val mostAtOnce = AtomicInteger(0)

        (1..50).map { n ->
            async(Dispatchers.Default) {
                writer.write {
                    mostAtOnce.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    db.languageDao.insert(language("l$n"))
                    Thread.sleep(1)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, mostAtOnce.get(), "two writes ran at the same time")
        assertEquals(50, db.languageDao.fetchAll().count { it.slug.startsWith("l") })
    }

    @Test
    fun `a write is one transaction that begins and ends on one thread`() = runBlocking {
        val threads = Collections.synchronizedList(mutableListOf<Thread>())

        assertFailsWith<IllegalStateException> {
            writer.write {
                threads += Thread.currentThread()
                db.languageDao.insert(language("first"))
                threads += Thread.currentThread()
                error("roll back")
            }
        }

        assertEquals(1, threads.distinct().size)
        assertFalse(db.languageDao.fetchAll().any { it.slug == "first" }, "the failed write was not rolled back")
    }

    @Test
    fun `Rx and coroutine writes run in the order they were submitted`() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<Int>())

        val jobs = (1..20).map { n ->
            if (n % 2 == 0) {
                launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { writer.write { order += n } }
            } else {
                writer.completable { order += n }.subscribe()
                null
            }
        }
        jobs.filterNotNull().forEach { it.join() }
        writer.write { }

        assertEquals((1..20).toList(), order.toList())
    }

    @Test
    fun `waiting on the queue from inside a write fails instead of hanging`() = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(10)) {
            assertFailsWith<ReentrantWriteException> {
                writer.write { runBlocking { writer.write { } } }
            }
            assertFailsWith<ReentrantWriteException> {
                writer.write { writer.completable { }.blockingAwait() }
            }
            assertFailsWith<ReentrantWriteException> {
                writer.write { writer.single { 1 }.blockingGet() }
            }
        }
        assertTrue(writer.write { true }, "the queue still works after a refused wait")
    }
}
