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

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightDatabaseMigrator
import org.bibletranslationtools.otter.db.OtterDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 5b's second on-device gate (docs/phase5b-handoff.md): proves [SqlDelightDatabaseMigrator]
 * runs the v0->14 upgrade path on the FRAMEWORK sqlite engine (API 24 / SQLite 3.9.2), including
 * the delicate 12->13 `take_entity` `DROP TABLE` / `ALTER ... RENAME` rebuild dance — a
 * table-rebuild-under-a-transaction pattern that is exactly the kind of thing an old SQLite build
 * could plausibly choke on.
 *
 * Seeds a legacy v12 fixture (schema matches SqlDelightDatabaseMigratorDifferentialTest's v12
 * fixture in desktopTest: full v14 schema minus the checking infra, a Psalms collection slug
 * 'psa' label 'chapter', and a take) with plain `android.database.sqlite.SQLiteDatabase`, then
 * opens the SAME file with a dumb-callback `AndroidSqliteDriver` (FKs on) and runs the migrator.
 */
@RunWith(AndroidJUnit4::class)
class SqlDelightAndroidMigrationTest {

    private lateinit var context: Context
    private val dbName = "legacy_v12_migration_test.sqlite"
    private lateinit var driver: SqlDriver

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        context.deleteDatabase(dbName)
    }

    // Plain identifier (no spaces): D8 dexing for minSdk 24 rejects space characters in method
    // SimpleNames ("prior to DEX version 040"), even though the .class compiles fine with backticks.
    @Test
    fun migratorUpgradesLegacyV12DbToV14OnDeviceRelabelsPsalmsAndPreservesTakes() {
        // 1) Seed a v12 legacy DB with plain framework SQLite (no SQLDelight involved yet).
        writeLegacyV12Fixture()

        // 2) Open the SAME file through a dumb-callback AndroidSqliteDriver (onCreate/onUpgrade
        // no-op -- the file already exists and is NOT at OtterDatabase.Schema.version, so letting
        // SQLDelight's automatic migration touch it would be wrong; our own migrator owns this).
        val callback = object : AndroidSqliteDriver.Callback(OtterDatabase.Schema) {
            override fun onCreate(db: SupportSQLiteDatabase) { /* no-op */ }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) { /* no-op */ }
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        }
        driver = AndroidSqliteDriver(OtterDatabase.Schema, context, name = dbName, callback = callback)

        // 3) Run the ported migrator directly.
        SqlDelightDatabaseMigrator(FakeTempFileProvider(context)).migrate(driver)

        // (a) version -> 14
        assertEquals(14, queryInt(driver, "SELECT version FROM installed_entity WHERE name = 'DATABASE'"))

        // (b) Psalms collection relabeled 'chapter' -> 'psalm' (13->14 step).
        assertEquals(
            "psalm",
            queryString(driver, "SELECT label FROM collection_entity WHERE slug = 'psa'")
        )

        // (c) the take rebuild (12->13) preserved the take row(s), stamped checking_fk to
        // UNCHECKED's id, and left checksum NULL -- the exact jOOQ quirk being replicated.
        val uncheckedId = queryInt(driver, "SELECT id FROM checking_status WHERE name = 'UNCHECKED'")
        assertEquals(1, queryInt(driver, "SELECT COUNT(*) FROM take_entity"))
        assertEquals(uncheckedId, queryInt(driver, "SELECT checking_fk FROM take_entity LIMIT 1"))
        assertNull(queryStringOrNull(driver, "SELECT checksum FROM take_entity LIMIT 1"))
    }

    // ── legacy v12 fixture (mirrors SqlDelightDatabaseMigratorDifferentialTest in desktopTest) ──

    private fun writeLegacyV12Fixture() {
        val db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        db.use { conn ->
            conn.beginTransaction()
            try {
                legacyV12SchemaStatements().forEach { conn.execSQL(it) }
                seedStatements().forEach { conn.execSQL(it) }
                conn.execSQL("INSERT INTO installed_entity (name, version) VALUES ('DATABASE', 12)")
                conn.setTransactionSuccessful()
            } finally {
                conn.endTransaction()
            }
        }
    }

    private fun legacyV12SchemaStatements(): List<String> = listOf(
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
        // v12 already has bridged/v_end (added at 10->11).
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
        """.trimIndent(),
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
        """
        CREATE TABLE versification_entity (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            slug            TEXT NOT NULL UNIQUE,
            path            TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE workbook_type (
            id               INTEGER PRIMARY KEY AUTOINCREMENT,
            name             TEXT NOT NULL,
            UNIQUE (name COLLATE NOCASE) ON CONFLICT IGNORE
        )
        """.trimIndent(),
        """
        CREATE TABLE workbook_descriptor_entity (
            id                INTEGER PRIMARY KEY AUTOINCREMENT,
            source_FK         INTEGER NOT NULL REFERENCES collection_entity(id) ON DELETE CASCADE,
            target_FK         INTEGER NOT NULL REFERENCES collection_entity(id) ON DELETE CASCADE,
            type_fk           INTEGER NOT NULL REFERENCES workbook_type(id) ON DELETE RESTRICT,
            UNIQUE (source_FK, target_FK, type_fk)
        )
        """.trimIndent(),
        "CREATE INDEX idx_content_entity_collection_start ON content_entity (collection_fk, start, type_fk)",
        "CREATE INDEX idx_content_derivative_content ON content_derivative (content_fk)",
        "CREATE INDEX idx_content_derivative_source ON content_derivative (source_fk)",
        "CREATE INDEX idx_resource_link_collection ON resource_link (collection_fk)",
        "CREATE INDEX idx_resource_link_content ON resource_link (content_fk)",
    )

    /** language -> dublin_core -> Psalms collection (slug 'psa', label 'chapter') -> content -> 1 take. */
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
    )

    // ── driver query helpers ──────────────────────────────────────────────────────────────────

    private fun queryInt(driver: SqlDriver, sql: String): Int =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                val value = if (cursor.next().value) cursor.getLong(0)?.toInt() else null
                QueryResult.Value(value)
            },
            0
        ).value ?: error("no row for: $sql")

    private fun queryString(driver: SqlDriver, sql: String): String =
        queryStringOrNull(driver, sql) ?: error("no row for: $sql")

    private fun queryStringOrNull(driver: SqlDriver, sql: String): String? =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                val value = if (cursor.next().value) cursor.getString(0) else null
                QueryResult.Value(value)
            },
            0
        ).value

    /** Minimal on-device [ITempFileProvider]: extractSelectedTakeInfo (current<=8, not exercised
     *  by this v12 fixture) just needs somewhere writable to land its output file. */
    private class FakeTempFileProvider(context: Context) : ITempFileProvider {
        override val tempDirectory: File = context.cacheDir
        override fun createTempFile(prefix: String, suffix: String?): File =
            File.createTempFile(prefix, suffix, tempDirectory)
        override fun cleanTempDirectory() { /* no-op for this test */ }
    }
}
