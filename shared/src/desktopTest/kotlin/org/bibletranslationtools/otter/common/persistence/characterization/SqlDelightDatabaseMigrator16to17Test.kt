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

/** Schema v16 → v17: `collection_entity.structure_edition_fk`. */
class SqlDelightDatabaseMigrator16to17Test {

    private val files = mutableListOf<File>()

    @AfterTest
    fun tearDown() = files.forEach { it.delete() }

    private fun freshDatabase(): File =
        File.createTempFile("migrator-17-", ".sqlite").also { file ->
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

    /** A fresh database with collection_entity rebuilt as v16 had it (SQLite can't drop a column with a foreign key). */
    private fun v16Database(): File = freshDatabase().also { file ->
        connect(file) { conn ->
            conn.exec("PRAGMA foreign_keys = OFF")
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
            conn.exec("UPDATE installed_entity SET version = 16 WHERE name = 'DATABASE'")
        }
    }

    private fun migrate(file: File) =
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use {
            SqlDelightDatabaseMigrator(mockk<ITempFileProvider>(relaxed = true)).migrate(it)
        }

    private fun schema(file: File) = connect(file) { conn ->
        listOf(conn.rows("PRAGMA table_info(collection_entity)"), conn.rows("PRAGMA foreign_key_list(collection_entity)"))
    }

    @Test
    fun `a v16 database gains the structure edition column`() {
        val migrated = v16Database().also(::migrate)

        assertEquals(schema(freshDatabase()), schema(migrated))
        connect(migrated) { conn ->
            assertEquals(listOf(listOf("$SCHEMA_VERSION")), conn.rows("SELECT version FROM installed_entity WHERE name = 'DATABASE'"))
        }
    }

    @Test
    fun `a database that already has the column is left as it is`() {
        val file = freshDatabase()
        connect(file) { it.exec("UPDATE installed_entity SET version = 16 WHERE name = 'DATABASE'") }

        migrate(file)

        assertEquals(schema(freshDatabase()), schema(file))
    }
}
