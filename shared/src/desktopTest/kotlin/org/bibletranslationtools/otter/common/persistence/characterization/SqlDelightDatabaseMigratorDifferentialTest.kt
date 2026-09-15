/**
 * Copyright (C) 2020-2024 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.persistence.characterization

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.mockk
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.persistence.database.AppDatabase
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightDatabaseMigrator
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 5a's real gate (docs/phase5a-handoff.md): the differential migrator test. jOOQ's
 * `DatabaseMigrator` is the oracle. This builds byte-identical hand-authored legacy fixtures (v12
 * and v10, "CreateAppDb.sql rewound"), runs jOOQ's migrator on one copy and
 * [SqlDelightDatabaseMigrator] on the other, and asserts the results are equivalent:
 *  (a) both land on version 14,
 *  (b) every table has the same *set* of columns, matching name/affinity/nullability — NOT cid
 *      order, since ALTER-appended columns land in different physical positions on each side (fine:
 *      SQLDelight's generated queries expand `SELECT *` to name-qualified columns and read by name),
 *  (c) every table holds identical row data.
 *
 * A divergence here means the ported [SqlDelightDatabaseMigrator] SQL is wrong — never weaken these
 * assertions to pass; debug the migrator against the jOOQ result instead.
 */
class SqlDelightDatabaseMigratorDifferentialTest {

    @Test
    fun `v12 fixture (take rebuild + psalm relabel) migrates identically to jOOQ`() {
        assertMigratesIdentically(version = 12, includeBridgedVEnd = true, includeWorkbookTables = true)
    }

    @Test
    fun `v10 fixture (clearProjectTables + new tables) migrates identically to jOOQ`() {
        assertMigratesIdentically(version = 10, includeBridgedVEnd = false, includeWorkbookTables = false)
    }

    // ── the differential ──────────────────────────────────────────────────────────────────────

    private fun assertMigratesIdentically(version: Int, includeBridgedVEnd: Boolean, includeWorkbookTables: Boolean) {
        val fileA = File.createTempFile("legacy-v$version-jooq-", ".sqlite")
        val fileB = File.createTempFile("legacy-v$version-sqldelight-", ".sqlite")
        try {
            writeFixture(fileA, version, includeBridgedVEnd, includeWorkbookTables)
            fileA.copyTo(fileB, overwrite = true)

            // A: run jOOQ's own DatabaseMigrator (the oracle) via the real AppDatabase init path.
            val jooqDirectoryProvider = mockk<IDirectoryProvider>(relaxed = true)
            AppDatabase(fileA, jooqDirectoryProvider).close()

            // B: run the ported SqlDelightDatabaseMigrator directly over a SqlDelight driver.
            val driver = JdbcSqliteDriver("jdbc:sqlite:${fileB.absolutePath}")
            driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
            SqlDelightDatabaseMigrator(mockk<ITempFileProvider>(relaxed = true)).migrate(driver)
            driver.close()

            // (a) same version.
            assertEquals(14, readVersion(fileA), "jOOQ oracle did not reach v14")
            assertEquals(14, readVersion(fileB), "SqlDelightDatabaseMigrator did not reach v14")

            // (b) same columns by name (set equality ignores cid/physical order).
            val schemaA = dumpSchema(fileA)
            val schemaB = dumpSchema(fileB)
            assertEquals(schemaA.keys, schemaB.keys, "table set differs between the two migrated DBs")
            for (table in schemaA.keys) {
                assertEquals(schemaA[table], schemaB[table], "schema for table '$table' differs")
            }

            // (c) same row data in every table.
            for (table in schemaA.keys) {
                assertEquals(
                    dumpRows(fileA, table),
                    dumpRows(fileB, table),
                    "row data for table '$table' differs"
                )
            }
        } finally {
            fileA.delete()
            fileB.delete()
        }
    }

    // ── fixture construction ──────────────────────────────────────────────────────────────────

    /**
     * "CreateAppDb.sql rewound" to a legacy version: the full v14 schema minus what later
     * migrations add, stamped with [version] in installed_entity, seeded with a representative
     * project chain (language -> dublin_core -> a Psalms collection, slug 'psa' / label 'chapter'
     * -> content -> two take rows) so both the take rebuild and the psalm-relabel steps, or
     * clearProjectTables, have real data to act on.
     */
    private fun writeFixture(file: File, version: Int, includeBridgedVEnd: Boolean, includeWorkbookTables: Boolean) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                legacySchemaStatements(includeBridgedVEnd, includeWorkbookTables).forEach { st.execute(it) }
                seedStatements().forEach { st.execute(it) }
                st.execute("INSERT INTO installed_entity (name, version) VALUES ('DATABASE', $version)")
            }
        }
    }

    private fun legacySchemaStatements(includeBridgedVEnd: Boolean, includeWorkbookTables: Boolean): List<String> {
        val statements = mutableListOf(
            """
            CREATE TABLE language_entity (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              slug          TEXT  NOT NULL UNIQUE,
              name          TEXT NOT NULL,
              gateway       INTEGER DEFAULT 0 NOT NULL,
              anglicized    TEXT NOT NULL,
              direction     TEXT NOT NULL,
              region        TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE dublin_core_entity (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                conformsTo  TEXT NOT NULL,
                creator     TEXT NOT NULL,
                description TEXT NOT NULL,
                format      TEXT NOT NULL,
                identifier  TEXT NOT NULL,
                issued      TEXT NOT NULL,
                language_fk INTEGER NOT NULL REFERENCES language_entity(id),
                modified    TEXT NOT NULL,
                publisher   TEXT NOT NULL,
                subject     TEXT NOT NULL,
                type        TEXT NOT NULL,
                title       TEXT NOT NULL,
                version     TEXT NOT NULL,
                license     TEXT NOT NULL DEFAULT '',
                path        TEXT NOT NULL,
                derivedFrom_fk INTEGER REFERENCES dublin_core_entity(id),
                UNIQUE (language_fk, identifier, version, creator, derivedFrom_fk)
            )
            """.trimIndent(),
            """
            CREATE TABLE rc_link_entity (
                rc1_fk      INTEGER NOT NULL REFERENCES dublin_core_entity(id) ON DELETE CASCADE,
                rc2_fk      INTEGER NOT NULL REFERENCES dublin_core_entity(id) ON DELETE CASCADE,
                PRIMARY KEY (rc1_fk, rc2_fk),
                CONSTRAINT directionless CHECK (rc1_fk < rc2_fk)
            )
            """.trimIndent(),
            """
            CREATE TABLE collection_entity (
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
            """.trimIndent(),
            """
            CREATE TABLE content_type (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                name             TEXT NOT NULL,
                UNIQUE (name COLLATE NOCASE) ON CONFLICT IGNORE
            )
            """.trimIndent(),
            if (includeBridgedVEnd) {
                """
                CREATE TABLE content_entity (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    collection_fk    INTEGER NOT NULL REFERENCES collection_entity(id) ON DELETE CASCADE,
                    type_fk          INTEGER NOT NULL REFERENCES content_type(id) ON DELETE RESTRICT,
                    label            TEXT NOT NULL,
                    selected_take_fk INTEGER REFERENCES take_entity(id) ON DELETE SET NULL,
                    start            INTEGER NOT NULL,
                    v_end            INTEGER DEFAULT 0 NOT NULL,
                    sort             INTEGER NOT NULL,
                    text             TEXT,
                    format           TEXT,
                    draft_number     INTEGER DEFAULT 1 NOT NULL,
                    bridged          INTEGER DEFAULT 0 NOT NULL
                )
                """.trimIndent()
            } else {
                """
                CREATE TABLE content_entity (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    collection_fk    INTEGER NOT NULL REFERENCES collection_entity(id) ON DELETE CASCADE,
                    type_fk          INTEGER NOT NULL REFERENCES content_type(id) ON DELETE RESTRICT,
                    label            TEXT NOT NULL,
                    selected_take_fk INTEGER REFERENCES take_entity(id) ON DELETE SET NULL,
                    start            INTEGER NOT NULL,
                    sort             INTEGER NOT NULL,
                    text             TEXT,
                    format           TEXT,
                    draft_number     INTEGER DEFAULT 1 NOT NULL
                )
                """.trimIndent()
            },
            """
            CREATE TABLE content_derivative (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                content_fk       INTEGER NOT NULL REFERENCES content_entity(id) ON DELETE CASCADE,
                source_fk        INTEGER NOT NULL REFERENCES content_entity(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            // Pre-v13 shape: no checking_fk / checksum, no checking_status table (added in 12->13).
            """
            CREATE TABLE take_entity (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                content_fk       INTEGER NOT NULL REFERENCES content_entity(id) ON DELETE CASCADE,
                filename         TEXT NOT NULL,
                path             TEXT NOT NULL,
                number           INTEGER NOT NULL,
                created_ts       TEXT NOT NULL,
                deleted_ts       TEXT DEFAULT NULL,
                played           INTEGER DEFAULT 0 NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE marker_entity (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                take_fk          INTEGER NOT NULL REFERENCES take_entity(id) ON DELETE CASCADE,
                number           INTEGER NOT NULL,
                position         INTEGER NOT NULL,
                label            TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE resource_link (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                resource_content_fk INTEGER NOT NULL REFERENCES content_entity(id) ON DELETE CASCADE,
                content_fk          INTEGER REFERENCES content_entity(id) ON DELETE CASCADE,
                collection_fk       INTEGER REFERENCES collection_entity(id) ON DELETE CASCADE,
                dublin_core_fk      INTEGER NOT NULL REFERENCES dublin_core_entity(id),
                UNIQUE (resource_content_fk, content_fk, collection_fk) ON CONFLICT IGNORE,
                CONSTRAINT ensure_at_least_one_not_null
                    CHECK ((collection_fk is NOT NULL) or (content_fk is NOT NULL)),
                CONSTRAINT prevent_both_not_null
                    CHECK ((collection_fk is NULL) or (content_fk is NULL))
            )
            """.trimIndent(),
            """
            CREATE TABLE subtree_has_resource (
              collection_fk       INTEGER NOT NULL REFERENCES collection_entity (id) ON DELETE CASCADE,
              dublin_core_fk      INTEGER NOT NULL REFERENCES dublin_core_entity (id) ON DELETE CASCADE,
              PRIMARY KEY (collection_fk, dublin_core_fk) ON CONFLICT IGNORE
            )
            """.trimIndent(),
            // audio_plugin_entity already has `mark` (added 1->2, well before 10).
            """
            CREATE TABLE audio_plugin_entity (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                name                TEXT NOT NULL,
                version             TEXT NOT NULL,
                bin                 TEXT NOT NULL,
                args                TEXT NOT NULL,
                record              INTEGER DEFAULT 0 NOT NULL,
                edit                INTEGER DEFAULT 0 NOT NULL,
                mark                INTEGER DEFAULT 0 NOT NULL,
                path                TEXT,
                UNIQUE (name, version)
            )
            """.trimIndent(),
            """
            CREATE TABLE preferences (
                key                 TEXT NOT NULL UNIQUE,
                value               TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE installed_entity (
                name                TEXT PRIMARY KEY NOT NULL,
                version             INTEGER NOT NULL
            )
            """.trimIndent(),
            // translation_entity already has modified_ts / source_rate / target_rate (added by 6, 8).
            """
            CREATE TABLE translation_entity (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                source_fk        INTEGER NOT NULL REFERENCES language_entity(id) ON DELETE CASCADE,
                target_fk        INTEGER NOT NULL REFERENCES language_entity(id) ON DELETE CASCADE,
                modified_ts      TEXT DEFAULT NULL,
                source_rate      DOUBLE DEFAULT 1.0,
                target_rate      DOUBLE DEFAULT 1.0,
                UNIQUE (source_fk, target_fk)
            )
            """.trimIndent(),
            // versification_entity was added at 9->10, so it already exists at v10 and v12.
            """
            CREATE TABLE versification_entity (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                slug            TEXT NOT NULL UNIQUE,
                path            TEXT NOT NULL
            )
            """.trimIndent(),
        )

        if (includeWorkbookTables) {
            statements += """
                CREATE TABLE workbook_type (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    name             TEXT NOT NULL,
                    UNIQUE (name COLLATE NOCASE) ON CONFLICT IGNORE
                )
            """.trimIndent()
            statements += """
                CREATE TABLE workbook_descriptor_entity (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_FK         INTEGER NOT NULL REFERENCES collection_entity(id) ON DELETE CASCADE,
                    target_FK         INTEGER NOT NULL REFERENCES collection_entity(id) ON DELETE CASCADE,
                    type_fk           INTEGER NOT NULL REFERENCES workbook_type(id) ON DELETE RESTRICT,
                    UNIQUE (source_FK, target_FK, type_fk)
                )
            """.trimIndent()
        }

        statements += "CREATE INDEX idx_content_entity_collection_start ON content_entity (collection_fk, start, type_fk)"
        statements += "CREATE INDEX idx_content_derivative_content ON content_derivative (content_fk)"
        statements += "CREATE INDEX idx_content_derivative_source ON content_derivative (source_fk)"
        statements += "CREATE INDEX idx_resource_link_collection ON resource_link (collection_fk)"
        statements += "CREATE INDEX idx_resource_link_content ON resource_link (content_fk)"

        return statements
    }

    /** language -> dublin_core -> Psalms collection (slug 'psa', label 'chapter') -> content -> 2 takes. */
    private fun seedStatements(): List<String> = listOf(
        """INSERT INTO language_entity (slug, name, gateway, anglicized, direction, region)
           VALUES ('en', 'English', 1, 'English', 'ltr', 'US')""",
        """INSERT INTO dublin_core_entity
           (conformsTo, creator, description, format, identifier, issued, language_fk, modified,
            publisher, subject, type, title, version, license, path)
           VALUES ('rc0.2', 'test', 'desc', 'text/usfm', 'psa', '2020-01-01', 1, '2020-01-01',
                   'pub', 'subj', 'book', 'Psalms', '1', '', '/tmp/psa')""",
        "INSERT INTO content_type (name) VALUES ('meta')",
        """INSERT INTO collection_entity (label, title, slug, sort, dublin_core_fk)
           VALUES ('chapter', 'Psalm 1', 'psa', 1, 1)""",
        """INSERT INTO content_entity (collection_fk, type_fk, label, start, sort)
           VALUES (1, 1, 'c1', 1, 1)""",
        """INSERT INTO take_entity (content_fk, filename, path, number, created_ts, deleted_ts, played)
           VALUES (1, 'take1.wav', '/tmp/take1.wav', 1, '2020-01-01T00:00:00Z', NULL, 0)""",
        """INSERT INTO take_entity (content_fk, filename, path, number, created_ts, deleted_ts, played)
           VALUES (1, 'take2.wav', '/tmp/take2.wav', 2, '2020-01-02T00:00:00Z', NULL, 1)""",
    )

    // ── introspection (adapted from SchemaParityTest) ────────────────────────────────────────────

    private data class ColumnInfo(val name: String, val affinity: String, val notNull: Boolean)

    private fun readVersion(file: File): Int? =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT version FROM installed_entity WHERE name = 'DATABASE'").use { rs ->
                    if (rs.next()) rs.getInt("version") else null
                }
            }
        }

    private fun dumpSchema(file: File): Map<String, Set<ColumnInfo>> =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            val tables = mutableListOf<String>()
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
                ).use { rs -> while (rs.next()) tables += rs.getString(1) }
            }
            tables.associateWith { table -> readColumns(conn, table) }
        }

    private fun readColumns(conn: Connection, table: String): Set<ColumnInfo> {
        val columns = mutableSetOf<ColumnInfo>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info('$table')").use { rs ->
                while (rs.next()) {
                    columns += ColumnInfo(
                        name = rs.getString("name"),
                        affinity = affinityOf(rs.getString("type")),
                        notNull = rs.getInt("notnull") == 1,
                    )
                }
            }
        }
        return columns
    }

    /** SQLite's column-affinity rules (https://www.sqlite.org/datatype3.html#determination_of_column_affinity). */
    private fun affinityOf(declaredType: String?): String {
        val t = (declaredType ?: "").uppercase()
        return when {
            t.contains("INT") -> "INTEGER"
            t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT") -> "TEXT"
            t.isEmpty() || t.contains("BLOB") -> "BLOB"
            t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") -> "REAL"
            else -> "NUMERIC"
        }
    }

    /** Reads every row of [table], column-by-name (order-independent) so ALTER-appended columns in
     *  different physical positions on each side don't cause a spurious mismatch. Ordered by rowid
     *  for a deterministic comparison. */
    private fun dumpRows(file: File, table: String): List<Map<String, Any?>> =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM \"$table\" ORDER BY rowid").use { rs ->
                    val meta = rs.metaData
                    val columnCount = meta.columnCount
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        rows += (1..columnCount).associate { i -> meta.getColumnName(i) to rs.getObject(i) }
                    }
                    rows
                }
            }
        }
}
