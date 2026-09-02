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
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import java.io.File

/**
 * Resolves the app database file, opens a driver for it via [driverFactory], and hands the resulting
 * [SqlDriver] to [SqlDelightAppDatabase.open]. This is the one place that decides whether the on-disk
 * file is new (fresh schema create) or existing (migrate), platform-independent — the platform only
 * supplies how to open a driver ([DatabaseDriverFactory]) and an optional post-open hook ([onOpened],
 * e.g. desktop's sandboxed-Mac path rewrite).
 */
class SqlDelightDatabaseProvider(
    private val driverFactory: DatabaseDriverFactory,
    private val directoryProvider: IDirectoryProvider,   // infra factory — allowed to take the composite
    private val databaseFileName: String = DATABASE_FILE_NAME,
    private val onOpened: (SqlDriver) -> Unit = {},
) {
    fun provide(): DaoProvider {
        val dbFile = directoryProvider.databaseDirectory.resolve(File(databaseFileName))
        val isNew = !dbFile.exists() || dbFile.length() == 0L
        val driver = driverFactory.create(dbFile)
        val database = SqlDelightAppDatabase.open(driver, isNew, directoryProvider)
        onOpened(driver)
        return database
    }
}
