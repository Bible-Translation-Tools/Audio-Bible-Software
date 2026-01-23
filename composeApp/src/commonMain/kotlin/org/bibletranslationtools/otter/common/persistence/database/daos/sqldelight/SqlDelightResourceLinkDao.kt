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
import org.bibletranslationtools.otter.common.persistence.database.Resource_link
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.ResourceLinkEntity

class SqlDelightResourceLinkDao(
    private val db: SqlDelightAppDatabase
) {
    private val queries = db.appDatabaseQueries

    fun fetchByContentId(id: Int): List<ResourceLinkEntity> {
        return queries.fetchResourceLinksByContent(id.toLong()).executeAsList().map { it.toResourceLinkEntity() }
    }

    fun fetchByCollectionId(id: Int): List<ResourceLinkEntity> {
        return queries.fetchResourceLinksByCollection(id.toLong()).executeAsList().map { it.toResourceLinkEntity() }
    }

    fun insert(entity: ResourceLinkEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")

        queries.insertResourceLink(
            resource_content_fk = entity.resourceContentFk.toLong(),
            content_fk = entity.contentFk?.toLong(),
            collection_fk = entity.collectionFk?.toLong(),
            dublin_core_fk = entity.dublinCoreFk.toLong()
        )

        return queries.lastInsertId().executeAsOne().toInt()
    }

    fun insertNoReturn(vararg entities: ResourceLinkEntity) {
        db.transaction {
            entities.forEach { entity ->
                if (entity.id != 0) throw InsertionException("Entity ID is not 0")
                queries.insertResourceLink(
                    resource_content_fk = entity.resourceContentFk.toLong(),
                    content_fk = entity.contentFk?.toLong(),
                    collection_fk = entity.collectionFk?.toLong(),
                    dublin_core_fk = entity.dublinCoreFk.toLong()
                )
            }
        }
    }

    fun fetchById(id: Int): ResourceLinkEntity {
        return queries.fetchResourceLinkById(id.toLong()).executeAsOne().toResourceLinkEntity()
    }

    fun fetchAll(): List<ResourceLinkEntity> {
        return queries.fetchAllResourceLinks().executeAsList().map { it.toResourceLinkEntity() }
    }

    fun update(entity: ResourceLinkEntity) {
        queries.updateResourceLink(
            resource_content_fk = entity.resourceContentFk.toLong(),
            content_fk = entity.contentFk?.toLong(),
            collection_fk = entity.collectionFk?.toLong(),
            dublin_core_fk = entity.dublinCoreFk.toLong(),
            id = entity.id.toLong()
        )
    }

    fun delete(entity: ResourceLinkEntity) {
        queries.deleteResourceLinkById(entity.id.toLong())
    }
}

fun Resource_link.toResourceLinkEntity(): ResourceLinkEntity {
    return ResourceLinkEntity(
        id = id.toInt(),
        resourceContentFk = resource_content_fk.toInt(),
        contentFk = content_fk?.toInt(),
        collectionFk = collection_fk?.toInt(),
        dublinCoreFk = dublin_core_fk.toInt()
    )
}
