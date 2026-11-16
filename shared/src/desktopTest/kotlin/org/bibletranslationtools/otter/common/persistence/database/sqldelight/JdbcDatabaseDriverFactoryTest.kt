package org.bibletranslationtools.otter.common.persistence.database.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JdbcDatabaseDriverFactoryTest {

    private val dbFile = File.createTempFile("factory-", ".sqlite").apply { delete() }

    @AfterTest
    fun cleanup() {
        listOf("", "-wal", "-shm", "-journal").forEach { File("${dbFile.absolutePath}$it").delete() }
    }

    private fun SqlDriver.pragma(name: String): String =
        executeQuery(null, "PRAGMA $name;", { c -> c.next(); QueryResult.Value(c.getString(0)!!) }, 0).value

    /**
     * An install from before this change was created on the rollback journal and, unless it was
     * seeded fresh, stayed there. Opening it now switches it to WAL, so reads can run while the
     * write queue writes, and connections wait for a lock instead of failing at once.
     */
    @Test
    fun `an existing rollback-journal database opens in WAL with a busy timeout`() {
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}").also { legacy ->
            SqlDelightAppDatabase.createFresh(legacy)
            assertEquals("delete", legacy.pragma("journal_mode"))
            legacy.close()
        }

        val driver = JdbcDatabaseDriverFactory().create(dbFile)
        try {
            assertEquals("wal", driver.pragma("journal_mode"))
            assertEquals(JdbcDatabaseDriverFactory.BUSY_TIMEOUT_MILLIS.toString(), driver.pragma("busy_timeout"))
            assertEquals("1", driver.pragma("foreign_keys"))
        } finally {
            driver.close()
        }
    }
}
