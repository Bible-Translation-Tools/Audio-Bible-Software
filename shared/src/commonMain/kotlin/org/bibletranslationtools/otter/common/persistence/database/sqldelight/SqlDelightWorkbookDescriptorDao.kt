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

import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.bibletranslationtools.otter.common.persistence.database.dao.WorkbookDescriptorDao
import org.bibletranslationtools.otter.common.persistence.entities.WorkbookDescriptorEntity
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [WorkbookDescriptorDao]. Behavior mirrors the jOOQ WorkbookDescriptorDao,
 * including the `insert → SELECT max(id)` id retrieval.
 */
internal class SqlDelightWorkbookDescriptorDao(private val db: OtterDatabase) : WorkbookDescriptorDao {
    private val queries = db.workbookDescriptorQueries

    override fun fetch(sourceId: Int, targetId: Int, typeId: Int): WorkbookDescriptorEntity? =
        queries.fetch(sourceId, targetId, typeId).executeAsOneOrNull()?.toEntity()

    override fun fetchById(id: Int): WorkbookDescriptorEntity? =
        queries.fetchById(id).executeAsOneOrNull()?.toEntity()

    override fun fetchAll(): List<WorkbookDescriptorEntity> =
        queries.fetchAll().executeAsList().map { it.toEntity() }

    override fun insert(entity: WorkbookDescriptorEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID must be 0. Found ${entity.id}")
        return db.transactionWithResult {
            queries.insert(
                sourceFk = entity.sourceFk,
                targetFk = entity.targetFk,
                typeFk = entity.typeFk,
            )
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    override fun update(entity: WorkbookDescriptorEntity) {
        queries.update(
            sourceFk = entity.sourceFk,
            targetFk = entity.targetFk,
            typeFk = entity.typeFk,
            id = entity.id,
        )
    }

    override fun delete(entity: WorkbookDescriptorEntity) {
        queries.delete(entity.id)
    }
}
