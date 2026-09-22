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
import org.bibletranslationtools.otter.common.persistence.database.dao.CollectionDao
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [CollectionDao]. Behavior mirrors the jOOQ CollectionDao, including the
 * `insert → SELECT max(id)` id retrieval wrapped in a single transaction.
 */
internal class SqlDelightCollectionDao(private val db: OtterDatabase) : CollectionDao {
    private val queries = db.collectionQueries

    override fun fetchChildren(entity: CollectionEntity): List<CollectionEntity> =
        queries.fetchChildren(entity.id).executeAsList().map { it.toEntity() }

    override fun fetchSource(entity: CollectionEntity): CollectionEntity? =
        entity.sourceFk?.let { queries.fetchById(it).executeAsOneOrNull()?.toEntity() }

    override fun fetch(slug: String, containerId: Int, label: String): CollectionEntity? =
        queries.fetch(slug, containerId, label).executeAsOneOrNull()?.toEntity()

    override fun insert(entity: CollectionEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")
        return db.transactionWithResult {
            queries.insert(
                parentFk = entity.parentFk,
                sourceFk = entity.sourceFk,
                slug = entity.slug,
                title = entity.title,
                label = entity.label,
                sort = entity.sort,
                dublinCoreFk = entity.dublinCoreFk!!,
                modifiedTs = entity.modifiedTs,
            )
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    override fun fetchById(id: Int): CollectionEntity =
        queries.fetchById(id).executeAsOne().toEntity()

    override fun fetchAll(): List<CollectionEntity> =
        queries.fetchAll().executeAsList().map { it.toEntity() }

    override fun fetchByIds(ids: List<Int>): List<CollectionEntity> {
        if (ids.isEmpty()) return emptyList()
        return queries.fetchByIds(ids).executeAsList().map { it.toEntity() }
    }

    override fun fetchByLabel(label: String): List<CollectionEntity> =
        queries.fetchByLabel(label).executeAsList().map { it.toEntity() }

    override fun update(entity: CollectionEntity) {
        queries.update(
            parentFk = entity.parentFk,
            sourceFk = entity.sourceFk,
            slug = entity.slug,
            title = entity.title,
            label = entity.label,
            sort = entity.sort,
            dublinCoreFk = entity.dublinCoreFk!!,
            modifiedTs = entity.modifiedTs,
            id = entity.id,
        )
    }

    override fun delete(entity: CollectionEntity) {
        queries.delete(entity.id)
    }

    override fun collectionsWithoutTakes(projectEntity: CollectionEntity): List<CollectionEntity> =
        queries.collectionsWithoutTakes(projectEntity.id).executeAsList().map { it.toEntity() }

    override fun copyChapters(sourceId: Int, projectId: Int, metadataId: Int) {
        queries.copyChapters(projectId = projectId, metadataId = metadataId, sourceId = sourceId)
    }

    override fun selectSourceLinkedRc2Fks(projectId: Int): List<Int> =
        queries.selectSourceLinkedRc2Fks(projectId).executeAsList()
}
