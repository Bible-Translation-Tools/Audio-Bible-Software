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
import org.bibletranslationtools.otter.common.persistence.database.Collection_entity
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.CollectionEntity

class SqlDelightCollectionDao(
    private val db: SqlDelightAppDatabase
) {
    private val queries = db.appDatabaseQueries

    fun fetchChildren(entity: CollectionEntity): List<CollectionEntity> {
        return queries.fetchChildren(entity.id.toLong()).executeAsList().map { it.toCollectionEntity() }
    }

    fun fetchSource(entity: CollectionEntity): CollectionEntity? {
        val sourceFk = entity.sourceFk ?: return null
        return queries.fetchSource(sourceFk.toLong()).executeAsOneOrNull()?.toCollectionEntity()
    }

    fun fetch(
        slug: String,
        containerId: Int,
        label: String = "project"
    ): CollectionEntity? {
        return queries.fetchBySlugAndDC(slug, containerId.toLong(), label).executeAsOneOrNull()?.toCollectionEntity()
    }

    fun insert(entity: CollectionEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")

        queries.insertCollection(
            parent_fk = entity.parentFk?.toLong(),
            source_fk = entity.sourceFk?.toLong(),
            label = entity.label,
            title = entity.title,
            slug = entity.slug,
            sort = entity.sort.toLong(),
            dublin_core_fk = entity.dublinCoreFk?.toLong() ?: 0L, // Assuming 0 as non-null dummy if needed, but schema says NOT NULL
            modified_ts = entity.modifiedTs
        )

        return queries.lastInsertId().executeAsOne().toInt()
    }

    fun fetchById(id: Int): CollectionEntity? {
        return queries.fetchCollectionById(id.toLong()).executeAsOneOrNull()?.toCollectionEntity()
    }

    fun fetchAll(): List<CollectionEntity> {
        return queries.fetchAllCollections().executeAsList().map { it.toCollectionEntity() }
    }

    fun fetchByLabel(label: String): List<CollectionEntity> {
        return queries.fetchByLabel(label).executeAsList().map { it.toCollectionEntity() }
    }

    fun update(entity: CollectionEntity) {
        queries.updateCollection(
            parent_fk = entity.parentFk?.toLong(),
            source_fk = entity.sourceFk?.toLong(),
            slug = entity.slug,
            title = entity.title,
            label = entity.label,
            sort = entity.sort.toLong(),
            dublin_core_fk = entity.dublinCoreFk?.toLong() ?: 0L,
            modified_ts = entity.modifiedTs,
            id = entity.id.toLong()
        )
    }

    fun delete(entity: CollectionEntity) {
        queries.deleteCollectionById(entity.id.toLong())
    }

    fun collectionsWithoutTakes(projectEntity: CollectionEntity): List<CollectionEntity> {
        return queries.collectionsWithoutTakes(projectEntity.id.toLong()).executeAsList().map { it.toCollectionEntity() }
    }
}

fun Collection_entity.toCollectionEntity(): CollectionEntity {
    return CollectionEntity(
        id = id.toInt(),
        parentFk = parent_fk?.toInt(),
        sourceFk = source_fk?.toInt(),
        label = label,
        title = title,
        slug = slug,
        sort = sort.toInt(),
        dublinCoreFk = dublin_core_fk.toInt(),
        modifiedTs = modified_ts
    )
}
