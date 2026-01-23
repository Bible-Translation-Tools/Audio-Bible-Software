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
package org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight

import org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.config.Installable

class SqlDelightInstalledEntityDao(
    private val db: SqlDelightAppDatabase
) {
    private val queries = db.appDatabaseQueries

    fun upsert(entity: Installable) {
        db.transaction {
            val existing = queries.fetchInstalledVersion(entity.name).executeAsOneOrNull()
            if (existing != null) {
                queries.updateInstalledVersion(entity.version.toLong(), entity.name)
            } else {
                queries.insertInstalledEntity(entity.name, entity.version.toLong())
            }
        }
    }

    fun fetchVersion(entity: Installable): Int? {
        return queries.fetchInstalledVersion(entity.name).executeAsOneOrNull()?.toInt()
    }
}
