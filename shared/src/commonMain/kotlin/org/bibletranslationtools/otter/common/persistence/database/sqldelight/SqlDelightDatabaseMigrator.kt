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
package org.bibletranslationtools.otter.common.persistence.database.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.primitives.ContentLabel
import org.bibletranslationtools.otter.common.data.primitives.PSALMS_SLUG
import org.bibletranslationtools.otter.common.persistence.database.DATABASE_INSTALLABLE_NAME
import org.bibletranslationtools.otter.common.persistence.database.SCHEMA_VERSION
import org.bibletranslationtools.otter.common.utils.SELECTED_TAKES_FROM_DB
import org.slf4j.LoggerFactory
import java.io.File
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus as CheckingStatusEnum

/**
 * Raw-SQL port of [org.bibletranslationtools.otter.common.persistence.database.DatabaseMigrator],
 * running the identical v0->14 upgrade path through a SQLDelight [SqlDriver] instead of jOOQ.
 *
 * This mirrors the jOOQ migrator's control flow AND its quirks exactly (see
 * docs/phase5a-handoff.md), including the two deliberate ones in the 12->13 take rebuild (the
 * rebuilt `take_entity` has no `content_fk` foreign key, and `checking_fk` is nullable with a
 * default rather than `NOT NULL`) and the try/catch-swallow-and-return-the-pre-step-version
 * behavior on steps 7->8, 8->9, 10->11, 12->13 and 13->14. jOOQ's `DataAccessException` catch
 * becomes a plain `Exception` catch here since raw JDBC/SQLDelight errors aren't wrapped the same
 * way.
 */
class SqlDelightDatabaseMigrator(
    private val directoryProvider: ITempFileProvider
) {
    private val logger = LoggerFactory.getLogger(SqlDelightDatabaseMigrator::class.java)

    fun migrate(driver: SqlDriver) {
        var current = currentVersion(driver)
        if (current != SCHEMA_VERSION) {
            if (current <= 8) { // Ot1
                extractSelectedTakeInfo(driver)
            }
            current = migrate0to1(driver, current)
            current = migrate1to2(driver, current)
            current = migrate2to3(driver, current)
            current = migrate3to4(driver, current)
            current = migrate4to5(driver, current)
            current = migrate5to6(driver, current)
            current = migrate6to7(driver, current)
            current = migrate7to8(driver, current)
            current = migrate8to9(driver, current)
            current = migrate9to10(driver, current)
            current = migrate10to11(driver, current)
            current = migrate11to12(driver, current)
            current = migrate12to13(driver, current)
            current = migrate13to14(driver, current)
            exec(driver, "UPDATE installed_entity SET version = $current WHERE name = '$DATABASE_INSTALLABLE_NAME'")
        }
    }

    // ── driver helpers ────────────────────────────────────────────────────────────────────────

    private fun exec(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0)
    }

    /** [SELECT version FROM installed_entity WHERE name = 'DATABASE'], or 0 if the row (or the
     *  table itself, on an ancient DB) is absent — jOOQ's `getDatabaseVersion` returns 0 via
     *  `fetchExists`. */
    private fun currentVersion(driver: SqlDriver): Int {
        return try {
            queryIntOrNull(driver, "SELECT version FROM installed_entity WHERE name = '$DATABASE_INSTALLABLE_NAME'") ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** Runs a single-column, single-row SELECT and returns the Int value, or null if no row matched. */
    private fun queryIntOrNull(driver: SqlDriver, sql: String): Int? {
        return driver.executeQuery(
            null,
            sql,
            { cursor ->
                val value = if (cursor.next().value) cursor.getLong(0)?.toInt() else null
                QueryResult.Value(value)
            },
            0
        ).value
    }

    /**
     * Extracts the selected take files from an Ot1 database due to potentially out-of-date
     * selected.txt.
     */
    private fun extractSelectedTakeInfo(driver: SqlDriver) {
        val paths = mutableListOf<String>()
        driver.executeQuery(
            null,
            """
            SELECT take_entity.path FROM content_entity
            JOIN take_entity ON content_entity.selected_take_fk = take_entity.id
            """.trimIndent(),
            { cursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { paths += it }
                }
                QueryResult.Value(Unit)
            },
            0
        )
        val filePathsToSave = paths.map { File(it).canonicalPath }
        directoryProvider.tempDirectory.resolve(SELECTED_TAKES_FROM_DB)
            .writeText(filePathsToSave.joinToString("\n"))
    }

    /**
     * Version 1
     * introduces the database itself as an "installed entity" to store the version number
     * to facilitate future database migrations
     */
    private fun migrate0to1(driver: SqlDriver, current: Int): Int {
        return if (current < 1) {
            exec(driver, "INSERT INTO installed_entity (name, version) VALUES ('$DATABASE_INSTALLABLE_NAME', 1);")
            logger.info("Updated database from version 0 to 1")
            1
        } else current
    }

    /**
     * Version 2
     * Adds a column for the marker plugin to the audio plugin table
     */
    private fun migrate1to2(driver: SqlDriver, current: Int): Int {
        return if (current < 2) {
            exec(driver, "ALTER TABLE audio_plugin_entity ADD COLUMN mark INTEGER DEFAULT 0 NOT NULL;")
            logger.info("Updated database from version 1 to 2")
            2
        } else current
    }

    /**
     * Version 3
     * Adds a column for the region to the languages table
     */
    private fun migrate2to3(driver: SqlDriver, current: Int): Int {
        return if (current < 3) {
            exec(driver, "ALTER TABLE language_entity ADD COLUMN region TEXT;")
            logger.info("Updated database from version 2 to 3")
            3
        } else current
    }

    /**
     * Version 4
     * Create translation table
     */
    private fun migrate3to4(driver: SqlDriver, current: Int): Int {
        return if (current < 4) {
            exec(
                driver,
                """
                CREATE TABLE IF NOT EXISTS translation_entity (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    source_fk INTEGER NOT NULL REFERENCES language_entity(id),
                    target_fk INTEGER NOT NULL REFERENCES language_entity(id),
                    UNIQUE (source_fk, target_fk)
                );
                """.trimIndent()
            )
            logger.info("Updated database from version 3 to 4")
            4
        } else current
    }

    /**
     * Version 5
     * Adds a column for the rights to the dublin core table
     */
    private fun migrate4to5(driver: SqlDriver, current: Int): Int {
        return if (current < 5) {
            exec(driver, "ALTER TABLE dublin_core_entity ADD COLUMN license TEXT NOT NULL DEFAULT '';")
            logger.info("Updated database from version 4 to 5")
            5
        } else current
    }

    /**
     * Version 6
     * Adds a column for the modified timestamp to the translations table
     */
    private fun migrate5to6(driver: SqlDriver, current: Int): Int {
        return if (current < 6) {
            exec(driver, "ALTER TABLE translation_entity ADD COLUMN modified_ts TEXT DEFAULT NULL;")
            logger.info("Updated database from version 5 to 6")
            6
        } else current
    }

    /**
     * Version 7
     * Adds a column for the modified timestamp to the collection table
     */
    private fun migrate6to7(driver: SqlDriver, current: Int): Int {
        return if (current < 7) {
            exec(driver, "ALTER TABLE collection_entity ADD COLUMN modified_ts TEXT DEFAULT NULL;")
            logger.info("Updated database from version 6 to 7")
            7
        } else current
    }

    /**
     * Version 8
     * Adds a column for the source rate and target rate to the translations table
     *
     * Exceptions are swallowed because the column might already exist but an existence check
     * cannot be performed in sqlite.
     */
    private fun migrate7to8(driver: SqlDriver, current: Int): Int {
        return if (current < 8) {
            try {
                exec(driver, "ALTER TABLE translation_entity ADD COLUMN source_rate REAL DEFAULT 1.0;")
                exec(driver, "ALTER TABLE translation_entity ADD COLUMN target_rate REAL DEFAULT 1.0;")
                logger.info("Updated database from version 7 to 8")
            } catch (e: Exception) {
                logger.error("Error in migrate7to8", e)
            }
            8
        } else current
    }

    /**
     * Version 9
     * Adds a column for the draft number to the collections table
     *
     * Exceptions are swallowed because the column might already exist but an existence check
     * cannot be performed in sqlite.
     */
    private fun migrate8to9(driver: SqlDriver, current: Int): Int {
        return if (current < 9) {
            try {
                exec(driver, "ALTER TABLE content_entity ADD COLUMN draft_number INTEGER DEFAULT 1 NOT NULL;")
                logger.info("Updated database from version 8 to 9")
                9
            } catch (e: Exception) {
                logger.error("Error in migrate8to9", e)
                8
            }
        } else current
    }

    /**
     * Version 10
     * Adds a table for Versification
     */
    private fun migrate9to10(driver: SqlDriver, current: Int): Int {
        return if (current < 10) {
            exec(
                driver,
                """
                CREATE TABLE IF NOT EXISTS versification_entity (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    slug TEXT NOT NULL,
                    path TEXT NOT NULL,
                    UNIQUE (slug)
                );
                """.trimIndent()
            )
            logger.info("Updated database from version 9 to 10")
            10
        } else current
    }

    /**
     * Version 11
     * Adds a column for the bridged and v_end to the content table
     *
     * The tables related to projects are truncated, which effectively is deleting the database. This is because
     * verse bridges and verse end are difficult to construct and migration code is nontrivial. As projects existing
     * in the project directory but not in the database are re-imported, this serves as an alternative to database
     * migrations here.
     *
     * Exceptions are swallowed because the column might already exist but an existence check cannot
     * be performed in sqlite.
     */
    private fun migrate10to11(driver: SqlDriver, current: Int): Int {
        return if (current < 11) {
            try {
                exec(driver, "ALTER TABLE content_entity ADD COLUMN bridged INTEGER DEFAULT 0 NOT NULL;")
                exec(driver, "ALTER TABLE content_entity ADD COLUMN v_end INTEGER DEFAULT 0 NOT NULL;")

                clearProjectTables(driver)

                logger.info("Updated database from version 10 to 11")
                11
            } catch (e: Exception) {
                logger.error("Error in migrate10to11", e)
                10
            }
        } else current
    }

    /**
     * Version 12
     * Adds WorkbookDescriptor table and WorkbookType table
     */
    private fun migrate11to12(driver: SqlDriver, current: Int): Int {
        return if (current < 12) {
            exec(
                driver,
                """
                CREATE TABLE IF NOT EXISTS workbook_type (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, UNIQUE (name)
                );
                """.trimIndent()
            )
            exec(
                driver,
                """
                CREATE TABLE IF NOT EXISTS workbook_descriptor_entity (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    source_FK INTEGER NOT NULL REFERENCES collection_entity(id) ON DELETE CASCADE,
                    target_FK INTEGER NOT NULL REFERENCES collection_entity(id) ON DELETE CASCADE,
                    type_fk   INTEGER NOT NULL REFERENCES workbook_type(id),
                    UNIQUE (source_FK, target_FK, type_fk)
                );
                """.trimIndent()
            )
            logger.info("Updated database from version 11 to 12")
            12
        } else current
    }

    /**
     * Version 13
     * Adds Checking Status table and Take Entity's FK column reference.
     *
     * Since ADD CONSTRAINT is not supported in SQLite - https://www.sqlite.org/omitted.html, the
     * data is copied to another table (same fields), the original table dropped and the new table
     * renamed back to the original one. Two deliberate quirks are preserved from the jOOQ migrator
     * (see docs/phase5a-handoff.md): the rebuilt `take_entity` has NO `content_fk` foreign key
     * (only the checking-status FK), and `checking_fk` is nullable with a default rather than
     * `NOT NULL`. Exceptions are swallowed because the column/table might already exist but an
     * existence check cannot be performed in sqlite.
     */
    private fun migrate12to13(driver: SqlDriver, current: Int): Int {
        return if (current < 13) {
            exec(
                driver,
                """
                CREATE TABLE IF NOT EXISTS checking_status (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, UNIQUE (name)
                );
                """.trimIndent()
            )
            exec(
                driver,
                "INSERT INTO checking_status (name) VALUES ('UNCHECKED'), ('PEER_EDIT'), ('KEYWORD'), ('VERSE');"
            )

            /** Default value for new column in Take Entity */
            val uncheckedId = queryIntOrNull(
                driver,
                "SELECT id FROM checking_status WHERE name = '${CheckingStatusEnum.UNCHECKED.name}'"
            )

            try {
                exec(
                    driver,
                    """
                    CREATE TABLE take_entity_temp (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content_fk INTEGER NOT NULL,
                        filename TEXT NOT NULL,
                        path TEXT NOT NULL,
                        number INTEGER NOT NULL,
                        created_ts TEXT NOT NULL,
                        deleted_ts TEXT,
                        played INTEGER DEFAULT 0 NOT NULL,
                        checking_fk INTEGER DEFAULT $uncheckedId,
                        checksum TEXT,
                        CONSTRAINT fk_checking_status FOREIGN KEY (checking_fk) REFERENCES checking_status(id)
                    );
                    """.trimIndent()
                )

                exec(
                    driver,
                    """
                    INSERT INTO take_entity_temp
                        SELECT id, content_fk, filename, path, number, created_ts, deleted_ts, played, $uncheckedId, NULL
                        FROM take_entity;
                    """.trimIndent()
                )

                exec(driver, "DROP TABLE take_entity;")
                exec(driver, "ALTER TABLE take_entity_temp RENAME TO take_entity;")
            } catch (e: Exception) {
                logger.error("Error in while migrating database from version 12 to 13", e)
                return 12
            }
            logger.info("Updated database from version 12 to 13")
            13
        } else current
    }

    /**
     * Version 14
     * Renames 'chapter' labels of Psalms collections to 'psalm'.
     */
    private fun migrate13to14(driver: SqlDriver, current: Int): Int {
        return if (current < 14) {
            try {
                exec(
                    driver,
                    "UPDATE collection_entity SET label = '${ContentLabel.PSALM.value}' " +
                        "WHERE label = '${ContentLabel.CHAPTER.value}' AND slug LIKE '$PSALMS_SLUG%';"
                )
            } catch (e: Exception) {
                logger.error("Error in while migrating database from version 13 to 14", e)
                return 13
            }
            logger.info("Updated database from version 13 to 14")
            14
        } else current
    }

    private fun clearProjectTables(driver: SqlDriver) {
        exec(driver, "DELETE FROM take_entity;")
        exec(driver, "DELETE FROM content_derivative;")
        exec(driver, "DELETE FROM content_entity;")
        exec(driver, "DELETE FROM collection_entity;")
        exec(driver, "DELETE FROM resource_link;")
        exec(driver, "DELETE FROM dublin_core_entity;")
    }
}
