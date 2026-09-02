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
import org.bibletranslationtools.otter.common.persistence.database.dao.MarkerDao
import org.bibletranslationtools.otter.common.persistence.entities.MarkerEntity
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [MarkerDao]. Behavior mirrors the jOOQ MarkerDao, including the
 * `insert → SELECT max(id)` id retrieval (wrapped in a transaction) and the id-must-be-0 guard.
 */
internal class SqlDelightMarkerDao(private val db: OtterDatabase) : MarkerDao {
    private val queries = db.markerQueries

    override fun fetchByTakeId(id: Int): List<MarkerEntity> =
        queries.fetchByTakeId(id).executeAsList().map { it.toEntity() }

    override fun insert(entity: MarkerEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")
        return db.transactionWithResult {
            queries.insert(
                takeFk = entity.takeFk!!,
                number = entity.number,
                position = entity.position,
                label = entity.label,
            )
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    override fun fetchById(id: Int): MarkerEntity =
        queries.fetchById(id).executeAsOne().toEntity()

    override fun fetchAll(): List<MarkerEntity> =
        queries.fetchAll().executeAsList().map { it.toEntity() }

    override fun update(entity: MarkerEntity) {
        queries.update(
            takeFk = entity.takeFk!!,
            number = entity.number,
            position = entity.position,
            label = entity.label,
            id = entity.id,
        )
    }

    override fun delete(entity: MarkerEntity) {
        queries.delete(entity.id)
    }
}
