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
import org.bibletranslationtools.otter.common.persistence.database.dao.ResourceLinkDao
import org.bibletranslationtools.otter.common.persistence.entities.ResourceLinkEntity
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [ResourceLinkDao]. Behavior mirrors the jOOQ ResourceLinkDao, including the
 * `insert → SELECT max(id)` id retrieval and the batched, no-return insert wrapped in a single
 * transaction.
 */
internal class SqlDelightResourceLinkDao(private val db: OtterDatabase) : ResourceLinkDao {
    private val queries = db.resourceLinkQueries

    override fun fetchByContentId(id: Int): List<ResourceLinkEntity> =
        queries.fetchByContentId(id).executeAsList().map { it.toEntity() }

    override fun fetchByCollectionId(id: Int): List<ResourceLinkEntity> =
        queries.fetchByCollectionId(id).executeAsList().map { it.toEntity() }

    override fun insert(entity: ResourceLinkEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")
        return db.transactionWithResult {
            queries.insert(
                resourceContentFk = entity.resourceContentFk,
                contentFk = entity.contentFk,
                collectionFk = entity.collectionFk,
                dublinCoreFk = entity.dublinCoreFk,
            )
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    override fun insertNoReturn(vararg entities: ResourceLinkEntity) {
        db.transaction {
            entities.forEach {
                if (it.id != 0) throw InsertionException("Entity ID is not 0")
                queries.insert(
                    resourceContentFk = it.resourceContentFk,
                    contentFk = it.contentFk,
                    collectionFk = it.collectionFk,
                    dublinCoreFk = it.dublinCoreFk,
                )
            }
        }
    }

    override fun fetchById(id: Int): ResourceLinkEntity =
        queries.fetchById(id).executeAsOne().toEntity()

    override fun fetchAll(): List<ResourceLinkEntity> =
        queries.fetchAll().executeAsList().map { it.toEntity() }

    override fun update(entity: ResourceLinkEntity) {
        queries.update(
            resourceContentFk = entity.resourceContentFk,
            contentFk = entity.contentFk,
            collectionFk = entity.collectionFk,
            dublinCoreFk = entity.dublinCoreFk,
            id = entity.id,
        )
    }

    override fun delete(entity: ResourceLinkEntity) {
        queries.delete(entity.id)
    }

    override fun copyResourceLinks(
        sourceMetadataId: Int,
        derivedMetadataId: Int,
        projectId: Int,
        projectDublinCoreFk: Int,
    ) {
        queries.copyResourceLinks(
            derivedMetadataId = derivedMetadataId,
            projectId = projectId,
            projectDublinCoreFk = projectDublinCoreFk,
            sourceMetadataId = sourceMetadataId,
        )
    }

    override fun insertLinkableVerses(
        dublinCoreId: Int,
        parentCollectionId: Int,
        mainTypeIds: Collection<Int>,
        helpTypeIds: Collection<Int>,
    ) {
        queries.insertLinkableVerses(
            dublinCoreId = dublinCoreId,
            parentCollectionId = parentCollectionId,
            mainTypeIds = mainTypeIds,
            helpTypeIds = helpTypeIds,
        )
    }

    override fun insertLinkableChapters(dublinCoreId: Int, collectionId: Int, helpTypeIds: Collection<Int>) {
        queries.insertLinkableChapters(
            dublinCoreId = dublinCoreId,
            collectionId = collectionId,
            helpTypeIds = helpTypeIds,
        )
    }

    override fun contentResourceMetadataFksByCollection(collectionId: Int): List<Int> =
        db.contentQueries.contentResourceMetadataFksByCollection(collectionId).executeAsList()
}
