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
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Schema v15 → v16: one row per source edition, enforced by a partial unique index on source rows
 * by language, identifier, creator and both fingerprints.
 */
class SqlDelightDatabaseMigrator15to16Test {

    private val files = mutableListOf<File>()

    @AfterTest
    fun tearDown() = files.forEach { it.delete() }

    private fun freshDatabase(): File =
        File.createTempFile("migrator-16-", ".sqlite").also { file ->
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

    private fun migrate(file: File) =
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use {
            SqlDelightDatabaseMigrator(mockk<ITempFileProvider>(relaxed = true)).migrate(it)
        }

    private fun Connection.insertLanguage() =
        exec("INSERT INTO language_entity (slug, name, anglicized, direction, gateway, region) VALUES ('en', 'English', 'English', 'ltr', 1, '')")

    /** A source row (or a derived one, with [derivedFrom]) with the given version and fingerprints. */
    private fun Connection.insertEdition(
        version: String,
        structure: String?,
        text: String?,
        creator: String = "WA",
        derivedFrom: Int? = null
    ) = exec(
        "INSERT INTO dublin_core_entity (conformsTo, creator, description, format, identifier, issued, language_fk, " +
            "modified, publisher, subject, type, title, version, path, derivedFrom_fk, structure_fingerprint, text_fingerprint) " +
            "VALUES ('rc0.2', '$creator', '', 'text/usfm', 'ulb', '2017-11-29', 1, '2017-11-29', '', '', 'bundle', 'ULB', " +
            "'$version', '/rc/$version', ${derivedFrom ?: "NULL"}, ${structure?.let { "'$it'" } ?: "NULL"}, ${text?.let { "'$it'" } ?: "NULL"})"
    )

    @Test
    fun `a v15 database gains the source edition index`() {
        val file = freshDatabase()
        connect(file) { conn ->
            conn.exec("DROP INDEX idx_dublin_core_source_edition")
            conn.exec("UPDATE installed_entity SET version = 15 WHERE name = 'DATABASE'")
        }

        migrate(file)

        connect(file) { conn ->
            assertEquals(listOf(listOf("$SCHEMA_VERSION")), conn.rows("SELECT version FROM installed_entity WHERE name = 'DATABASE'"))
            assertEquals(
                connect(freshDatabase()) { it.rows("PRAGMA index_list(dublin_core_entity)") },
                conn.rows("PRAGMA index_list(dublin_core_entity)")
            )
        }
    }

    /** The two English "v12" zips: same label, different content. Both must install. */
    @Test
    fun `two editions with the same label and different content are allowed`() {
        connect(freshDatabase()) { conn ->
            conn.insertLanguage()
            conn.insertEdition("12", "s1", "t1")
            conn.insertEdition("12", "s2", "t2")

            assertEquals(listOf(listOf("2")), conn.rows("SELECT COUNT(*) FROM dublin_core_entity"))
        }
    }

    @Test
    fun `the same edition twice is refused, whatever its label`() {
        connect(freshDatabase()) { conn ->
            conn.insertLanguage()
            conn.insertEdition("12", "s1", "t1")

            assertFailsWith<SQLException> { conn.insertEdition("12.1", "s1", "t1") }
        }
    }

    @Test
    fun `a different creator is a different edition`() {
        connect(freshDatabase()) { conn ->
            conn.insertLanguage()
            conn.insertEdition("12", "s1", "t1", creator = "WA")
            conn.insertEdition("12", "s1", "t1", creator = "unfoldingWord")

            assertEquals(listOf(listOf("2")), conn.rows("SELECT COUNT(*) FROM dublin_core_entity"))
        }
    }

    /** Rows imported before fingerprints existed wait for the backfill; they must not collide. */
    @Test
    fun `rows without a fingerprint aren't constrained`() {
        connect(freshDatabase()) { conn ->
            conn.insertLanguage()
            conn.insertEdition("12", null, null)
            conn.insertEdition("13", null, null)

            assertEquals(listOf(listOf("2")), conn.rows("SELECT COUNT(*) FROM dublin_core_entity"))
        }
    }
}
