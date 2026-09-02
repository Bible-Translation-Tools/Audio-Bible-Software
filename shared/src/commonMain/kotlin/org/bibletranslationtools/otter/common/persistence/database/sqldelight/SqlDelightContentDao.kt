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

import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.bibletranslationtools.otter.common.persistence.database.dao.ContentDao
import org.bibletranslationtools.otter.common.persistence.database.dao.ContentTypeDao
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [ContentDao]. Behavior mirrors the jOOQ ContentDao, including the
 * `insert → SELECT max(id)` id retrieval (wrapped in a transaction) and the id-must-be-0 guard on
 * inserts. Content-type resolution is delegated to [contentTypeDao], as in the jOOQ implementation.
 */
internal class SqlDelightContentDao(
    private val db: OtterDatabase,
    private val contentTypeDao: ContentTypeDao
) : ContentDao {
    private val queries = db.contentQueries

    override fun fetchByCollectionId(collectionId: Int): List<ContentEntity> =
        queries.fetchByCollectionId(collectionId).executeAsList().map { it.toEntity() }

    override fun fetchByCollectionIdAndStart(
        collectionId: Int,
        start: Int,
        types: Collection<ContentType>
    ): List<ContentEntity> {
        val typeIds = types.map(contentTypeDao::fetchId)
        return queries.fetchByCollectionIdAndStart(collectionId, start, typeIds)
            .executeAsList()
            .map { it.toEntity() }
    }

    override fun fetchByCollectionIdAndType(
        collectionId: Int,
        type: ContentType
    ): List<ContentEntity> =
        queries.fetchByCollectionIdAndType(collectionId, contentTypeDao.fetchId(type))
            .executeAsList()
            .map { it.toEntity() }

    override fun fetchSources(entity: ContentEntity): List<ContentEntity> =
        queries.fetchSources(entity.id).executeAsList().map { it.toEntity() }

    override fun updateSources(entity: ContentEntity, sources: List<ContentEntity>) {
        db.transaction {
            queries.deleteDerivativesForContent(entity.id)
            sources.forEach { source ->
                queries.insertDerivative(entity.id, source.id)
            }
        }
    }

    override fun insert(entity: ContentEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID was not 0")
        return db.transactionWithResult {
            queries.insert(
                collectionFk = entity.collectionFk,
                sort = entity.sort,
                start = entity.start,
                vEnd = entity.end,
                label = entity.labelKey,
                selectedTakeFk = entity.selectedTakeFk,
                text = entity.text,
                format = entity.format,
                typeFk = entity.type_fk,
                draftNumber = entity.draftNumber,
                bridged = entity.bridged,
            )
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    override fun insertNoReturn(vararg entities: ContentEntity) {
        db.transaction {
            entities.forEach { e ->
                if (e.id != 0) throw InsertionException("Entity ID was not 0")
                queries.insert(
                    collectionFk = e.collectionFk,
                    sort = e.sort,
                    start = e.start,
                    vEnd = e.end,
                    label = e.labelKey,
                    selectedTakeFk = e.selectedTakeFk,
                    text = e.text,
                    format = e.format,
                    typeFk = e.type_fk,
                    draftNumber = e.draftNumber,
                    bridged = e.bridged,
                )
            }
        }
    }

    override fun fetchById(id: Int): ContentEntity =
        queries.fetchById(id).executeAsOne().toEntity()

    override fun fetchAll(): List<ContentEntity> =
        queries.fetchAll().executeAsList().map { it.toEntity() }

    override fun updateAll(entities: List<ContentEntity>) {
        db.transaction {
            entities.forEach { entity ->
                queries.updateForBulk(
                    sort = entity.sort,
                    label = entity.labelKey,
                    start = entity.start,
                    vEnd = entity.end,
                    selectedTakeFk = entity.selectedTakeFk,
                    text = entity.text,
                    format = entity.format,
                    typeFk = entity.type_fk,
                    draftNumber = entity.draftNumber,
                    bridged = entity.bridged,
                    id = entity.id,
                )
            }
        }
    }

    override fun update(entity: ContentEntity) {
        queries.update(
            sort = entity.sort,
            label = entity.labelKey,
            start = entity.start,
            vEnd = entity.end,
            collectionFk = entity.collectionFk,
            selectedTakeFk = entity.selectedTakeFk,
            text = entity.text,
            format = entity.format,
            draftNumber = entity.draftNumber,
            bridged = entity.bridged,
            id = entity.id,
        )
    }

    override fun delete(entity: ContentEntity) {
        queries.delete(entity.id)
    }

    override fun deleteForCollection(chapterCollection: CollectionEntity, contentTypeId: Int?) {
        queries.deleteForCollection(chapterCollection.id, contentTypeId ?: 1)
    }

    override fun linkDerivative(contentId: Int, sourceContentId: Int) {
        queries.insertDerivative(contentId, sourceContentId)
    }

    override fun copyContent(sourceId: Int, metadataId: Int) {
        queries.copyContent(sourceId = sourceId, metadataId = metadataId)
    }

    override fun copyMetaContent(sourceId: Int, metadataId: Int) {
        queries.copyMetaContent(sourceId = sourceId, metadataId = metadataId)
    }

    override fun linkDerivativeContent(sourceId: Int, projectId: Int) {
        queries.linkDerivativeContent(sourceId = sourceId, projectId = projectId)
    }

    override fun resourcesForContent(contentId: Int, dublinCoreId: Int): List<ContentEntity> =
        queries.resourcesForContent(dublinCoreId = dublinCoreId, contentId = contentId)
            .executeAsList()
            .map { it.toEntity() }

    override fun resourcesForCollection(collectionId: Int, dublinCoreId: Int): List<ContentEntity> =
        queries.resourcesForCollection(dublinCoreId = dublinCoreId, collectionId = collectionId)
            .executeAsList()
            .map { it.toEntity() }
}
