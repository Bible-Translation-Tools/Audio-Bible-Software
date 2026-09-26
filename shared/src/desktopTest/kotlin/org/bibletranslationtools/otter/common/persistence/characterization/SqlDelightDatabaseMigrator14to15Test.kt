package org.bibletranslationtools.otter.common.persistence.characterization

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.mockk
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.persistence.database.SCHEMA_VERSION
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightDatabaseMigrator
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Schema v14 → v15 (edition fingerprints). A v14 database is made by taking a fresh one and removing
 * what v15 and later added; migrating it must give the same tables as a fresh database.
 */
class SqlDelightDatabaseMigrator14to15Test {

    private val files = mutableListOf<File>()

    @AfterTest
    fun tearDown() = files.forEach { it.delete() }

    private fun freshDatabase(): File =
        File.createTempFile("migrator-15-", ".sqlite").also { file ->
            files.add(file)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { SqlDelightAppDatabase.createFresh(it) }
        }

    private fun <T> connect(file: File, block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use(block)

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    /** A fresh database rewound to v14, keeping [keepColumns] of the v15 ones (a partly-run step). */
    private fun v14Database(keepColumns: Set<String> = emptySet()): File =
        freshDatabase().also { file ->
            connect(file) { conn ->
                conn.exec("DROP INDEX idx_dublin_core_source_edition")
                conn.exec("DROP TABLE edition_chapter")
                listOf("detected_versification", "structure_fingerprint", "text_fingerprint")
                    .filter { it !in keepColumns }
                    .forEach { conn.exec("ALTER TABLE dublin_core_entity DROP COLUMN $it") }
                conn.exec("UPDATE installed_entity SET version = 14 WHERE name = 'DATABASE'")
            }
        }

    private fun migrate(file: File) =
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use {
            SqlDelightDatabaseMigrator(mockk<ITempFileProvider>(relaxed = true)).migrate(it)
        }

    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            generateSequence { if (rs.next()) (1..columns).map { rs.getString(it) } else null }.toList()
        }
    }

    private fun schema(file: File, table: String) = connect(file) { conn ->
        listOf(
            conn.rows("PRAGMA table_info($table)"),
            conn.rows("PRAGMA foreign_key_list($table)"),
            conn.rows("PRAGMA index_list($table)")
        )
    }

    private fun version(file: File) = connect(file) { conn ->
        conn.rows("SELECT version FROM installed_entity WHERE name = 'DATABASE'").single().single()
    }

    @Test
    fun `a v14 database migrates to the fresh v15 schema`() {
        val migrated = v14Database().also(::migrate)
        val fresh = freshDatabase()

        assertEquals("$SCHEMA_VERSION", version(migrated))
        listOf("dublin_core_entity", "edition_chapter").forEach { table ->
            assertEquals(schema(fresh, table), schema(migrated, table), "schema for '$table' differs")
        }
    }

    @Test
    fun `a step that failed partway completes on the next run`() {
        val migrated = v14Database(keepColumns = setOf("detected_versification")).also(::migrate)

        assertEquals("$SCHEMA_VERSION", version(migrated))
        assertEquals(schema(freshDatabase(), "dublin_core_entity"), schema(migrated, "dublin_core_entity"))
    }

    @Test
    fun `existing rows survive with no fingerprint`() {
        val file = v14Database()
        connect(file) { conn ->
            conn.exec("INSERT INTO language_entity (slug, name, anglicized, direction, gateway, region) VALUES ('en', 'English', 'English', 'ltr', 1, '')")
            conn.exec(
                "INSERT INTO dublin_core_entity (conformsTo, creator, description, format, identifier, issued, language_fk, modified, publisher, subject, type, title, version, path) " +
                    "VALUES ('rc0.2', 'c', '', 'text/usfm', 'ulb', '2017-11-29', 1, '2017-11-29', '', '', 'bundle', 'ULB', '12', '/rc')"
            )
        }

        migrate(file)

        connect(file) { conn ->
            assertEquals(
                listOf(listOf("ulb", "12", null)),
                conn.rows("SELECT identifier, version, structure_fingerprint FROM dublin_core_entity")
            )
        }
    }

    @Test
    fun `deleting an edition deletes its chapter fingerprints`() {
        val file = freshDatabase()
        connect(file) { conn ->
            conn.exec("PRAGMA foreign_keys = ON")
            conn.exec("INSERT INTO language_entity (slug, name, anglicized, direction, gateway, region) VALUES ('en', 'English', 'English', 'ltr', 1, '')")
            conn.exec(
                "INSERT INTO dublin_core_entity (conformsTo, creator, description, format, identifier, issued, language_fk, modified, publisher, subject, type, title, version, path) " +
                    "VALUES ('rc0.2', 'c', '', 'text/usfm', 'ulb', '2017-11-29', 1, '2017-11-29', '', '', 'bundle', 'ULB', '12', '/rc')"
            )
            conn.exec("INSERT INTO edition_chapter VALUES (1, 'gen_1', 's', 't')")

            conn.exec("DELETE FROM dublin_core_entity")

            assertEquals(emptyList(), conn.rows("SELECT * FROM edition_chapter"))
        }
    }
}
