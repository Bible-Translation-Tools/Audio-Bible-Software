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
import org.bibletranslationtools.otter.common.persistence.database.dao.TakeDao
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.TakeEntity
import org.bibletranslationtools.otter.db.OtterDatabase
import org.bibletranslationtools.otter.db.Take_entity

/**
 * SQLDelight-backed [TakeDao]. Behavior mirrors the jOOQ TakeDao, including the
 * `insert → SELECT max(id)` id retrieval wrapped in a single transaction.
 */
internal class SqlDelightTakeDao(private val db: OtterDatabase) : TakeDao {
    private val queries = db.takeQueries

    override fun fetchByContentId(id: Int, includeDeleted: Boolean): List<TakeEntity> =
        if (includeDeleted) {
            queries.fetchByContentIdIncludingDeleted(id).executeAsList().map { it.toEntity() }
        } else {
            queries.fetchByContentId(id).executeAsList().map { it.toEntity() }
        }

    override fun insert(entity: TakeEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")
        return db.transactionWithResult {
            queries.insert(
                contentFk = entity.contentFk,
                filename = entity.filename,
                path = entity.filepath,
                number = entity.number,
                createdTs = entity.createdTs,
                deletedTs = entity.deletedTs,
                played = entity.played,
                checkingFk = entity.checkingFk,
                checksum = entity.checksum,
            )
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    override fun fetchById(id: Int): TakeEntity =
        queries.fetchById(id).executeAsOne().toEntity()

    override fun fetchAll(): List<TakeEntity> =
        queries.fetchAll().executeAsList().map { it.toEntity() }

    override fun update(entity: TakeEntity) {
        queries.update(
            contentFk = entity.contentFk,
            filename = entity.filename,
            path = entity.filepath,
            number = entity.number,
            createdTs = entity.createdTs,
            deletedTs = entity.deletedTs,
            played = entity.played,
            checkingFk = entity.checkingFk,
            checksum = entity.checksum,
            id = entity.id,
        )
    }

    override fun delete(entity: TakeEntity) {
        queries.delete(entity.id)
    }

    override fun fetchSoftDeletedTakes(collectionEntity: CollectionEntity): List<TakeEntity> =
        queries.fetchSoftDeletedTakesForProject(collectionEntity.id, ::Take_entity)
            .executeAsList().map { it.toEntity() }

    override fun fetchSoftDeletedTakes(): List<TakeEntity> =
        queries.fetchSoftDeletedTakes(::Take_entity).executeAsList().map { it.toEntity() }

    override fun fetchByCollectionId(id: Int, includeDeleted: Boolean): List<TakeEntity> =
        if (includeDeleted) {
            queries.fetchByCollectionIdIncludingDeleted(id).executeAsList().map { it.toEntity() }
        } else {
            queries.fetchByCollectionId(id).executeAsList().map { it.toEntity() }
        }

    override fun deleteResourceTakesForProject(projectId: Int, projectSlug: String) {
        queries.deleteResourceTakesForProject(projectId = projectId, projectSlug = projectSlug)
    }
}
