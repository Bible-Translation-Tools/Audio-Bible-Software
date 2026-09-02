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

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.bibletranslationtools.otter.common.persistence.characterization.AbstractDatabaseCharacterizationTest.DatabaseBackend
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase

/**
 * The new (SQLDelight) backend. Builds a fresh in-memory database per test. Because it runs the same
 * [AbstractDatabaseCharacterizationTest] bodies as [JooqBackend] and must satisfy the same
 * assertions, green-on-both is the proof of functional equivalence.
 */
object SqlDelightBackend : DatabaseBackend {

    override fun createDatabase(): DaoProvider {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val provider = SqlDelightAppDatabase.createFresh(driver)
        open[provider] = driver
        return provider
    }

    override fun destroyDatabase(db: DaoProvider) {
        open.remove(db)?.close()
    }

    private val open = mutableMapOf<DaoProvider, SqlDriver>()
}
