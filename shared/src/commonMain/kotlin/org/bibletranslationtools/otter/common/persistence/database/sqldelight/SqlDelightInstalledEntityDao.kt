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

import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.persistence.database.dao.InstalledEntityDao
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [InstalledEntityDao]. Reproduces jOOQ's `onDuplicateKeyUpdate` upsert with an
 * update-then-insert-if-unchanged pair run atomically in a transaction.
 */
internal class SqlDelightInstalledEntityDao(private val db: OtterDatabase) : InstalledEntityDao {
    private val queries = db.installedEntityQueries

    override fun upsert(entity: Installable) {
        db.transaction {
            queries.upsertUpdate(version = entity.version, name = entity.name)
            queries.upsertInsertIfUnchanged(name = entity.name, version = entity.version)
        }
    }

    override fun fetchVersion(entity: Installable): Int? =
        queries.fetchVersion(entity.name).executeAsList().firstOrNull()
}
