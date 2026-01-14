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

import org.bibletranslationtools.otter.common.persistence.database.AppDatabase
import org.bibletranslationtools.otter.common.persistence.database.Take_entity
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.TakeEntity
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.CollectionEntity

class SqlDelightTakeDao(
    private val db: AppDatabase
) {
    private val queries = db.appDatabaseQueries

    fun fetchByContentId(
        id: Int,
        includeDeleted: Boolean = false
    ): List<TakeEntity> {
        return queries.fetchTakesByContent(id.toLong(), includeDeleted).executeAsList().map { 
            TakeEntity(
                id = it.id.toInt(),
                contentFk = it.content_fk.toInt(),
                filename = it.filename,
                filepath = it.path,
                number = it.number.toInt(),
                createdTs = it.created_ts,
                deletedTs = it.deleted_ts,
                played = it.played.toInt(),
                checkingFk = it.checking_fk.toInt(),
                checksum = it.checksum
            )
        }
    }

    fun insert(entity: TakeEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")

        queries.insertTake(
            content_fk = entity.contentFk.toLong(),
            filename = entity.filename,
            path = entity.filepath,
            number = entity.number.toLong(),
            created_ts = entity.createdTs,
            deleted_ts = entity.deletedTs,
            played = entity.played.toLong(),
            checking_fk = entity.checkingFk.toLong(),
            checksum = entity.checksum
        )

        return queries.lastInsertId().executeAsOne().toInt()
    }

    fun fetchById(id: Int): TakeEntity? {
        return queries.fetchTakeById(id.toLong()).executeAsOneOrNull()?.toTakeEntity()
    }

    fun fetchAll(): List<TakeEntity> {
        return queries.fetchAllTakes().executeAsList().map { it.toTakeEntity() }
    }

    fun update(entity: TakeEntity) {
        queries.updateTake(
            content_fk = entity.contentFk.toLong(),
            filename = entity.filename,
            path = entity.filepath,
            number = entity.number.toLong(),
            created_ts = entity.createdTs,
            deleted_ts = entity.deletedTs,
            played = entity.played.toLong(),
            checking_fk = entity.checkingFk.toLong(),
            checksum = entity.checksum,
            id = entity.id.toLong()
        )
    }

    fun delete(entity: TakeEntity) {
        queries.deleteTakeById(entity.id.toLong())
    }

    fun fetchSoftDeletedTakes(collectionEntity: CollectionEntity): List<TakeEntity> {
        return queries.fetchSoftDeletedTakesByProject(collectionEntity.id.toLong()).executeAsList().map { 
             TakeEntity(
                id = it.id.toInt(),
                contentFk = it.content_fk.toInt(),
                filename = it.filename,
                filepath = it.path,
                number = it.number.toInt(),
                createdTs = it.created_ts,
                deletedTs = it.deleted_ts,
                played = it.played.toInt(),
                checkingFk = it.checking_fk.toInt(),
                checksum = it.checksum
            )
        }
    }

    fun fetchSoftDeletedTakes(): List<TakeEntity> {
        return queries.fetchSoftDeletedTakes().executeAsList().map { 
             TakeEntity(
                id = it.id.toInt(),
                contentFk = it.content_fk.toInt(),
                filename = it.filename,
                filepath = it.path,
                number = it.number.toInt(),
                createdTs = it.created_ts,
                deletedTs = it.deleted_ts,
                played = it.played.toInt(),
                checkingFk = it.checking_fk.toInt(),
                checksum = it.checksum
            )
        }
    }

    fun fetchByCollectionId(
        id: Int,
        includeDeleted: Boolean = false
    ): List<TakeEntity> {
        return queries.fetchTakesByCollection(id.toLong(), includeDeleted).executeAsList().map { 
             TakeEntity(
                id = it.id.toInt(),
                contentFk = it.content_fk.toInt(),
                filename = it.filename,
                filepath = it.path,
                number = it.number.toInt(),
                createdTs = it.created_ts,
                deletedTs = it.deleted_ts,
                played = it.played.toInt(),
                checkingFk = it.checking_fk.toInt(),
                checksum = it.checksum
            )
        }
    }
}

fun Take_entity.toTakeEntity(): TakeEntity {
    return TakeEntity(
        id = id.toInt(),
        contentFk = content_fk.toInt(),
        filename = filename,
        filepath = path,
        number = number.toInt(),
        createdTs = created_ts,
        deletedTs = deleted_ts,
        played = played.toInt(),
        checkingFk = checking_fk.toInt(),
        checksum = checksum
    )
}
