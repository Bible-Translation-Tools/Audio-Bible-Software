package org.bibletranslationtools.otter.common.persistence.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * A blocking write made from inside a queued write joins that write's transaction.
 */
class WriteTransactionsTest {

    private val dbFile = File.createTempFile("transactions-", ".sqlite").apply { delete() }
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    private val db = SqlDelightAppDatabase.createFresh(driver)

    @AfterTest
    fun cleanup() {
        driver.close()
        listOf("", "-wal", "-shm", "-journal").forEach { File("${dbFile.absolutePath}$it").delete() }
    }

    @Test
    fun `a blocking write from inside a write joins it instead of waiting on itself`() = runBlocking {
        val writer = DatabaseWriter(db)
        fun language(slug: String) = LanguageEntity(0, slug, slug, slug, "ltr", 0, "")

        writer.write {
            db.languageDao.insert(language("outer"))
            writer.writeBlocking { db.languageDao.insert(language("inner")) }
        }

        assertEquals(setOf("outer", "inner"), db.languageDao.fetchAll().map { it.slug }.filter { it in setOf("outer", "inner") }.toSet())
    }

    @Test
    fun `a blocking write that joins a write rolls back with it`() = runBlocking {
        val writer = DatabaseWriter(db)
        fun language(slug: String) = LanguageEntity(0, slug, slug, slug, "ltr", 0, "")

        assertFails {
            writer.write {
                writer.writeBlocking { db.languageDao.insert(language("joined")) }
                error("outer fails")
            }
        }

        assertEquals(emptyList(), db.languageDao.fetchAll().filter { it.slug == "joined" })
    }
}
