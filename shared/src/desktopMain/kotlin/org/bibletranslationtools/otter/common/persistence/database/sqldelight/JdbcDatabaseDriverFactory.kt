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

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

/**
 * Desktop's [DatabaseDriverFactory]: a JDBC SQLite driver, foreign keys ON to match jOOQ's
 * cascades/restricts.
 *
 * The driver opens a connection per thread, and `PRAGMA foreign_keys` only applies to the
 * connection it runs on, so it is set as a connection property: sqlite-jdbc applies it to every
 * connection it opens. A one-off PRAGMA after opening left every other thread's connection without
 * foreign keys, so, for example, deleting a project on the IO scheduler left its chapters behind.
 */
class JdbcDatabaseDriverFactory : DatabaseDriverFactory {
    override fun create(databaseFile: File): SqlDriver =
        JdbcSqliteDriver(
            "jdbc:sqlite:${databaseFile.absolutePath}",
            Properties().apply { put("foreign_keys", "true") }
        )
}

/**
 * Updates the absolute paths in the database tables to the sand-boxed paths (if needed). This fixes
 * an error when installing the new version from the App Store over the existing dmg-installed build.
 * Raw-SQL port of jOOQ `AppDatabase.migratePathsForSandboxedMac`, run as a post-open step (only when
 * `orature.isPkgMac` is set) rather than inside the migrator itself. Self-guards on that system
 * property so it can be passed directly as [SqlDelightDatabaseProvider]'s `onOpened` hook.
 */
fun migratePathsForSandboxedMac(driver: SqlDriver) {
    if (System.getProperty("orature.isPkgMac") == null) return

    val oldPrefix = "/Library/Application Support/Orature/"
    val newPrefix = "/Library/Containers/org.wycliffeassociates.otter/Data/Library/Application Support/Orature/"
    listOf("dublin_core_entity", "take_entity", "versification_entity").forEach { table ->
        driver.execute(
            null,
            """
            UPDATE $table
            SET path = REPLACE(path, '$oldPrefix', '$newPrefix')
            WHERE path LIKE '%$oldPrefix%'
            AND path NOT LIKE '%/Library/Containers/%';
            """.trimIndent(),
            0
        )
    }
}
