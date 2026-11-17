package org.bibletranslationtools.otter.common.persistence.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Marker
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.database.dao.MarkerDao
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.bibletranslationtools.otter.common.persistence.entities.MarkerEntity
import org.bibletranslationtools.otter.common.persistence.repositories.TakeRepository
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.CollectionMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.MarkerMapper
import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Changes that touch several rows are one transaction on the write queue: a failure part way
 * leaves none of them behind.
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

    /** [db] with a marker table that refuses inserts. */
    private val failingMarkers = object : DaoProvider by db {
        override val markerDao: MarkerDao = object : MarkerDao by db.markerDao {
            override fun insert(entity: MarkerEntity): Int = error("marker insert failed")
        }
    }

    @Test
    fun `a take whose marker fails to save leaves no take row`() {
        val repository = TakeRepository(failingMarkers, DatabaseWriter(failingMarkers), MarkerMapper(), CollectionMapper())
        val content = Content(
            sort = 1, labelKey = "verse", start = 1, end = 1, text = null, format = null,
            type = ContentType.TEXT, draftNumber = 1, id = 1
        )
        val take = Take(
            filename = "t1.wav", path = File("t1.wav"), number = 1, created = LocalDate.now(), deleted = null,
            played = false, checkingStatus = CheckingStatus.UNCHECKED, checksum = null,
            markers = listOf(Marker(number = 1, position = 0, label = "1"))
        )

        assertFails { repository.insertForContent(take, content).blockingGet() }

        assertEquals(emptyList(), db.takeDao.fetchAll(), "the take row outlived its failed marker")
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
