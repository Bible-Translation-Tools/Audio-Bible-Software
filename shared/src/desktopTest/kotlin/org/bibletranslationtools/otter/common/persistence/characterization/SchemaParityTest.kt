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
import org.bibletranslationtools.otter.common.persistence.database.AppDatabase
import org.bibletranslationtools.otter.db.OtterDatabase
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 1 gate: the fresh schema SQLDelight creates from the `.sq` files must match, structurally,
 * the one the current jOOQ path bootstraps from `sql/CreateAppDb.sql`.
 *
 * The comparison is by PRAGMA introspection (tables, columns, indexes, foreign keys), normalized so
 * that cosmetic differences that do not change behavior are ignored — declared type is reduced to
 * SQLite affinity (so `DOUBLE` ≡ `REAL`), and defaults are unquoted (so `""` ≡ `''`). What it holds
 * the line on: every table; each column's name, affinity, NOT NULL, default value, and primary-key
 * position; every index's columns + uniqueness; and every foreign key's columns, target and
 * ON DELETE action.
 *
 * CHECK constraints and ON CONFLICT conflict-clauses are not exposed by PRAGMA; they are reproduced
 * verbatim in the `.sq` and verified behaviorally once the SQLDelight backend runs the shared
 * characterization suite (Phase 3).
 */
class SchemaParityTest {

    @Test
    fun `the SQLDelight schema matches the jOOQ schema`() {
        val jooq = introspect(::createJooqSchema)
        val sqlDelight = introspect(::createSqlDelightSchema)

        // Guard against a vacuous pass: CreateAppDb.sql defines 19 tables.
        assertEquals(19, jooq.size, "jOOQ schema did not create the expected tables")
        assertEquals(19, sqlDelight.size, "SQLDelight schema did not create the expected tables")

        // Compare table-by-table for a readable diff, then the table set itself.
        assertEquals(jooq.keys, sqlDelight.keys, "table set differs")
        for (table in jooq.keys) {
            assertEquals(jooq[table], sqlDelight[table], "schema for table '$table' differs")
        }
    }

    // ── schema builders ───────────────────────────────────────────────────────────────────────

    private fun createJooqSchema(file: File) {
        val directoryProvider = mockk<IDirectoryProvider>(relaxed = true)
        // Bootstraps from sql/CreateAppDb.sql and stamps schema v14; migrator no-ops on a fresh db.
        AppDatabase(file, directoryProvider).close()
    }

    private fun createSqlDelightSchema(file: File) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OtterDatabase.Schema.create(driver)
        driver.close()
    }

    /** Build the schema into a throwaway file via [create], then introspect it over a fresh JDBC connection. */
    private fun introspect(create: (File) -> Unit): Map<String, TableSchema> {
        val file = File.createTempFile("schema-parity-", ".sqlite").apply { delete() }
        try {
            create(file)
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { return readSchema(it) }
        } finally {
            file.delete()
        }
    }

    // ── introspection ─────────────────────────────────────────────────────────────────────────

    private data class ColumnSchema(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val default: String?,
        val pkPosition: Int,
    )

    private data class IndexSchema(val unique: Boolean, val columns: List<String>)

    private data class ForeignKeySchema(
        val fromColumns: List<String>,
        val table: String,
        val toColumns: List<String>,
        val onDelete: String,
    )

    private data class TableSchema(
        val columns: List<ColumnSchema>,
        val indexes: Set<IndexSchema>,
        val foreignKeys: Set<ForeignKeySchema>,
    )

    private fun readSchema(conn: Connection): Map<String, TableSchema> {
        val tables = mutableListOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            ).use { rs -> while (rs.next()) tables += rs.getString(1) }
        }
        return tables.associateWith { table ->
            TableSchema(
                columns = readColumns(conn, table),
                indexes = readIndexes(conn, table),
                foreignKeys = readForeignKeys(conn, table),
            )
        }
    }

    private fun readColumns(conn: Connection, table: String): List<ColumnSchema> {
        val columns = mutableListOf<ColumnSchema>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info('$table')").use { rs ->
                while (rs.next()) {
                    columns += ColumnSchema(
                        name = rs.getString("name"),
                        affinity = affinityOf(rs.getString("type")),
                        notNull = rs.getInt("notnull") == 1,
                        default = normalizeDefault(rs.getString("dflt_value")),
                        pkPosition = rs.getInt("pk"),
                    )
                }
            }
        }
        return columns
    }

    private fun readIndexes(conn: Connection, table: String): Set<IndexSchema> {
        data class IdxRef(val name: String, val unique: Boolean)
        val refs = mutableListOf<IdxRef>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list('$table')").use { rs ->
                while (rs.next()) refs += IdxRef(rs.getString("name"), rs.getInt("unique") == 1)
            }
        }
        return refs.map { ref ->
            val cols = mutableListOf<Pair<Int, String>>()
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_info('${ref.name}')").use { rs ->
                    while (rs.next()) cols += rs.getInt("seqno") to rs.getString("name")
                }
            }
            // Ignore index NAME (auto-index names are position-dependent); compare columns + uniqueness.
            IndexSchema(ref.unique, cols.sortedBy { it.first }.map { it.second })
        }.toSet()
    }

    private fun readForeignKeys(conn: Connection, table: String): Set<ForeignKeySchema> {
        // A composite FK spans multiple rows sharing an `id`; group by it.
        data class FkRow(val id: Int, val seq: Int, val table: String, val from: String, val to: String, val onDelete: String)
        val rows = mutableListOf<FkRow>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA foreign_key_list('$table')").use { rs ->
                while (rs.next()) {
                    rows += FkRow(
                        id = rs.getInt("id"),
                        seq = rs.getInt("seq"),
                        table = rs.getString("table"),
                        from = rs.getString("from"),
                        to = rs.getString("to"),
                        onDelete = rs.getString("on_delete"),
                    )
                }
            }
        }
        return rows.groupBy { it.id }.values.map { group ->
            val ordered = group.sortedBy { it.seq }
            ForeignKeySchema(
                fromColumns = ordered.map { it.from },
                table = ordered.first().table,
                toColumns = ordered.map { it.to },
                onDelete = ordered.first().onDelete,
            )
        }.toSet()
    }

    // ── normalization ─────────────────────────────────────────────────────────────────────────

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

    /** Unquote and null-normalize a default so `""` ≡ `''` and `DEFAULT NULL` ≡ no default. */
    private fun normalizeDefault(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.equals("NULL", ignoreCase = true)) return null
        val unquoted = when {
            trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'' -> trimmed.substring(1, trimmed.length - 1)
            trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"' -> trimmed.substring(1, trimmed.length - 1)
            else -> trimmed
        }
        return unquoted
    }
}
