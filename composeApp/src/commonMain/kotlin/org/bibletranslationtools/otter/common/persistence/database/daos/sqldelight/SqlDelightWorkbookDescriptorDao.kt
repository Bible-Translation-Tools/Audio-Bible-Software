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
import org.bibletranslationtools.otter.common.persistence.database.Workbook_descriptor_entity
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.WorkbookDescriptorEntity

class SqlDelightWorkbookDescriptorDao(
    private val db: SqlDelightAppDatabase
) {
    private val queries = db.appDatabaseQueries

    fun fetch(sourceId: Int, targetId: Int, typeId: Int): WorkbookDescriptorEntity? {
        return queries.fetchWorkbookDescriptor(sourceId.toLong(), targetId.toLong(), typeId.toLong()).executeAsOneOrNull()?.toWorkbookDescriptorEntity()
    }

    fun fetchById(id: Int): WorkbookDescriptorEntity? {
        return queries.fetchWorkbookDescriptorById(id.toLong()).executeAsOneOrNull()?.toWorkbookDescriptorEntity()
    }

    fun fetchAll(): List<WorkbookDescriptorEntity> {
        return queries.fetchAllWorkbookDescriptors().executeAsList().map { it.toWorkbookDescriptorEntity() }
    }

    fun insert(entity: WorkbookDescriptorEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID must be 0. Found ${entity.id}")

        queries.insertWorkbookDescriptor(
            source_FK = entity.sourceFk.toLong(),
            target_FK = entity.targetFk.toLong(),
            type_fk = entity.typeFk.toLong()
        )

        return queries.lastInsertId().executeAsOne().toInt()
    }

    fun update(entity: WorkbookDescriptorEntity) {
        queries.updateWorkbookDescriptor(
            source_FK = entity.sourceFk.toLong(),
            target_FK = entity.targetFk.toLong(),
            type_fk = entity.typeFk.toLong(),
            id = entity.id.toLong()
        )
    }

    fun delete(entity: WorkbookDescriptorEntity) {
        queries.deleteWorkbookDescriptorById(entity.id.toLong())
    }
}

fun Workbook_descriptor_entity.toWorkbookDescriptorEntity(): WorkbookDescriptorEntity {
    return WorkbookDescriptorEntity(
        id = id.toInt(),
        sourceFk = source_FK.toInt(),
        targetFk = target_FK.toInt(),
        typeFk = type_fk.toInt()
    )
}
