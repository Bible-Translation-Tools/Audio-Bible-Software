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

import io.mockk.mockk
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.characterization.AbstractDatabaseCharacterizationTest.DatabaseBackend
import org.bibletranslationtools.otter.common.persistence.database.AppDatabase
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.database.jooqcompat.JooqDaoProvider
import java.io.File

/**
 * The current (jOOQ) backend for the characterization suite, presented through the clean
 * [DaoProvider] interfaces via [JooqDaoProvider]. Builds a real desktop [AppDatabase] over a
 * throwaway on-disk SQLite file — the same code path production uses — so these tests pin the genuine
 * behavior the SQLDelight backend must reproduce.
 */
object JooqBackend : DatabaseBackend {

    override fun createDatabase(): DaoProvider {
        val dbFile = File.createTempFile("otter-characterization-", ".sqlite")
        val directoryProvider = mockk<IDirectoryProvider>(relaxed = true)
        val database = AppDatabase(dbFile, directoryProvider)
        val provider = JooqDaoProvider(database)
        open[provider] = database to dbFile
        return provider
    }

    override fun destroyDatabase(db: DaoProvider) {
        open.remove(db)?.let { (database, file) ->
            database.close()
            file.delete()
        }
    }

    private val open = mutableMapOf<DaoProvider, Pair<AppDatabase, File>>()
}
