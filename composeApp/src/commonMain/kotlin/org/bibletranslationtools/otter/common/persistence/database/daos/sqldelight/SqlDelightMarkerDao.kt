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
import org.bibletranslationtools.otter.common.persistence.database.Marker_entity
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.MarkerEntity

class SqlDelightMarkerDao(
    private val db: SqlDelightAppDatabase
) {
    private val queries = db.appDatabaseQueries

    fun fetchByTakeId(id: Int): List<MarkerEntity> {
        return queries.fetchMarkersByTake(id.toLong()).executeAsList().map { it.toMarkerEntity() }
    }

    fun insert(entity: MarkerEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")

        queries.insertMarker(
            take_fk = (entity.takeFk ?: 0).toLong(),
            number = entity.number.toLong(),
            position = entity.position.toLong(),
            label = entity.label
        )

        return queries.lastInsertId().executeAsOne().toInt()
    }

    fun fetchById(id: Int): MarkerEntity? {
        return queries.fetchMarkerById(id.toLong()).executeAsOneOrNull()?.toMarkerEntity()
    }

    fun fetchAll(): List<MarkerEntity> {
        return queries.fetchAllMarkers().executeAsList().map { it.toMarkerEntity() }
    }

    fun update(entity: MarkerEntity) {
        queries.updateMarker(
            take_fk = (entity.takeFk ?: 0).toLong(),
            number = entity.number.toLong(),
            position = entity.position.toLong(),
            label = entity.label,
            id = entity.id.toLong()
        )
    }

    fun delete(entity: MarkerEntity) {
        queries.deleteMarkerById(entity.id.toLong())
    }
}

fun Marker_entity.toMarkerEntity(): MarkerEntity {
    return MarkerEntity(
        id = id.toInt(),
        takeFk = take_fk.toInt(),
        number = number.toInt(),
        position = position.toInt(),
        label = label ?: ""
    )
}
