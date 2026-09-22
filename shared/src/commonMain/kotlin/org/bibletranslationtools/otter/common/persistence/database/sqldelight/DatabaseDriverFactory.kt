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
import java.io.File

/** The single source of truth for the app database's file name. */
const val DATABASE_FILE_NAME = "tr.sqlite"

/**
 * A platform's way to open a "dumb" [SqlDriver] over [databaseFile]: foreign keys ON, and NO automatic
 * schema management (our installed_entity-based SqlDelightDatabaseMigrator owns versioning; create-vs-migrate
 * is decided by SqlDelightAppDatabase.open, not the driver).
 */
interface DatabaseDriverFactory {
    fun create(databaseFile: File): SqlDriver
}
