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
 * An install on the last schema before source editions (v14) migrates to exactly the schema a new
 * install gets: every table's columns, foreign keys and indexes, and the database's indexes. The
 * per-step tests pin each step; this pins that together they add up to the fresh schema.
 */
class SqlDelightDatabaseMigratorParityTest {

    private val files = mutableListOf<File>()

    @AfterTest
    fun tearDown() = files.forEach { it.delete() }

    private fun freshDatabase(): File =
        File.createTempFile("migrator-parity-", ".sqlite").also { file ->
            files.add(file)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { SqlDelightAppDatabase.createFresh(it) }
        }

    private fun <T> connect(file: File, block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use(block)

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            generateSequence { if (rs.next()) (1..columns).map { rs.getString(it) } else null }.toList()
        }
    }

    /** A fresh database with everything v15 to v18 added taken out again. */
    private fun v14Database(): File = freshDatabase().also { file ->
        connect(file) { conn ->
            conn.exec("PRAGMA foreign_keys = OFF")
            // v17: collection_entity.structure_edition_fk. SQLite can't drop a column with a foreign key.
            conn.exec(
                """
                CREATE TABLE collection_v16 (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    parent_fk       INTEGER REFERENCES collection_entity(id) ON DELETE CASCADE,
                    source_fk       INTEGER REFERENCES collection_entity(id),
                    label           TEXT NOT NULL,
                    title           TEXT NOT NULL,
                    slug            TEXT NOT NULL,
                    sort            INTEGER NOT NULL,
                    dublin_core_fk  INTEGER NOT NULL REFERENCES dublin_core_entity(id),
                    modified_ts     TEXT DEFAULT NULL,
                    UNIQUE (slug, dublin_core_fk, label)
                )
                """.trimIndent()
            )
            conn.exec("DROP TABLE collection_entity")
            conn.exec("ALTER TABLE collection_v16 RENAME TO collection_entity")
            // v16: one row per source edition.
            conn.exec("DROP INDEX idx_dublin_core_source_edition")
            // v15: edition fingerprints.
            conn.exec("DROP TABLE edition_chapter")
            listOf("detected_versification", "structure_fingerprint", "text_fingerprint")
                .forEach { conn.exec("ALTER TABLE dublin_core_entity DROP COLUMN $it") }
            conn.exec("UPDATE installed_entity SET version = 14 WHERE name = 'DATABASE'")
        }
    }

    private fun migrate(file: File) =
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use {
            SqlDelightDatabaseMigrator(mockk<ITempFileProvider>(relaxed = true)).migrate(it)
        }

    private fun tables(file: File): List<String> = connect(file) { conn ->
        conn.rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { it.single()!! }
    }

    private fun indexes(file: File): List<String> = connect(file) { conn ->
        conn.rows("SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL ORDER BY name")
            .map { it.single()!! }
    }

    private fun schema(file: File, table: String) = connect(file) { conn ->
        listOf(
            conn.rows("PRAGMA table_info($table)"),
            conn.rows("PRAGMA foreign_key_list($table)"),
            conn.rows("PRAGMA index_list($table)").map { it.drop(1) } // seq numbers differ with creation order
        )
    }

    @Test
    fun `a v14 install migrates to the schema a new install gets`() {
        val migrated = v14Database().also(::migrate)
        val fresh = freshDatabase()

        assertEquals(
            listOf("$SCHEMA_VERSION"),
            connect(migrated) { it.rows("SELECT version FROM installed_entity WHERE name = 'DATABASE'").single() }
        )
        assertEquals(tables(fresh), tables(migrated))
        assertEquals(indexes(fresh), indexes(migrated))
        tables(fresh).forEach { table ->
            assertEquals(schema(fresh, table), schema(migrated, table), "schema for '$table' differs")
        }
    }
}
