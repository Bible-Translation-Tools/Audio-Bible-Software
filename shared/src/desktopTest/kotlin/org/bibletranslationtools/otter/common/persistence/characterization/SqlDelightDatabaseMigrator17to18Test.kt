package org.bibletranslationtools.otter.common.persistence.characterization

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.mockk
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightDatabaseMigrator
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Schema v17 → v18: rows left behind by projects deleted without foreign keys are removed. */
class SqlDelightDatabaseMigrator17to18Test {

    private val file: File = File.createTempFile("migrator-18-", ".sqlite")

    @AfterTest
    fun tearDown() {
        file.delete()
    }

    private fun <T> connect(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use(block)

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.count(table: String): Int =
        createStatement().use { st -> st.executeQuery("SELECT count(*) FROM $table").use { it.next(); it.getInt(1) } }

    @Test
    fun `a deleted project's chapters, verses, takes and markers are removed, and nothing else`() {
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { SqlDelightAppDatabase.createFresh(it) }
        connect { conn ->
            conn.exec("INSERT INTO language_entity (slug, name, anglicized, direction, gateway, region) VALUES ('en', 'English', 'English', 'ltr', 1, '')")
            conn.exec(
                "INSERT INTO dublin_core_entity (conformsTo, creator, description, format, identifier, issued, language_fk, modified, publisher, subject, type, title, version, path) " +
                    "VALUES ('rc0.2', 'c', '', 'text/usfm', 'ulb', '2017-11-29', 1, '2017-11-29', '', '', 'book', 'ULB', '12', '/rc')"
            )
            // A kept project (book 1, chapter 2) and a deleted one (book 3 is gone, chapter 4 is left).
            conn.exec("INSERT INTO collection_entity (id, parent_fk, label, title, slug, sort, dublin_core_fk) VALUES (1, NULL, 'project', 'Jude', 'jud', 1, 1)")
            conn.exec("INSERT INTO collection_entity (id, parent_fk, label, title, slug, sort, dublin_core_fk) VALUES (2, 1, 'chapter', '1', 'jud_1', 1, 1)")
            conn.exec("INSERT INTO collection_entity (id, parent_fk, label, title, slug, sort, dublin_core_fk) VALUES (3, NULL, 'project', 'Acts', 'act', 2, 1)")
            conn.exec("INSERT INTO collection_entity (id, parent_fk, label, title, slug, sort, dublin_core_fk) VALUES (4, 3, 'chapter', '1', 'act_1', 1, 1)")
            listOf(2, 4).forEach { chapter ->
                conn.exec("INSERT INTO content_entity (collection_fk, type_fk, label, start, sort) VALUES ($chapter, 1, 'verse', 1, 1)")
            }
            conn.exec("INSERT INTO take_entity (content_fk, filename, path, number, created_ts, checking_fk) SELECT id, 't.wav', '/t.wav', 1, '2026', 1 FROM content_entity")
            conn.exec("INSERT INTO marker_entity (take_fk, number, position, label) SELECT id, 1, 0, '1' FROM take_entity")
            conn.exec("INSERT INTO workbook_descriptor_entity (source_FK, target_FK, type_fk) VALUES (1, 1, 1), (1, 3, 1)")
            // Deleted without foreign keys, as desktop used to.
            conn.exec("PRAGMA foreign_keys = OFF")
            conn.exec("DELETE FROM collection_entity WHERE id = 3")
            conn.exec("UPDATE installed_entity SET version = 17 WHERE name = 'DATABASE'")
        }

        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use {
            SqlDelightDatabaseMigrator(mockk<ITempFileProvider>(relaxed = true)).migrate(it)
        }

        connect { conn ->
            assertEquals(2, conn.count("collection_entity"), "the kept project's book and chapter remain")
            assertEquals(1, conn.count("content_entity"))
            assertEquals(1, conn.count("take_entity"))
            assertEquals(1, conn.count("marker_entity"))
            assertEquals(1, conn.count("workbook_descriptor_entity"))
        }
    }
}
