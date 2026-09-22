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

import app.cash.sqldelight.db.SqlDriver
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.bibletranslationtools.otter.common.persistence.database.dao.ContentDao
import org.bibletranslationtools.otter.common.persistence.database.dao.ContentTypeDao
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.db.OtterDatabase

/** content_entity's `insert` has this many columns; drives the Android-7 999-bound-parameter chunk size. */
private const val CONTENT_INSERT_COLUMNS = 11

/**
 * SQLDelight-backed [ContentDao]. Behavior mirrors the jOOQ ContentDao, including the
 * `insert → SELECT max(id)` id retrieval (wrapped in a transaction) and the id-must-be-0 guard on
 * inserts. Content-type resolution is delegated to [contentTypeDao], as in the jOOQ implementation.
 */
internal class SqlDelightContentDao(
    private val db: OtterDatabase,
    private val driver: SqlDriver,
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

    /**
     * Chunked multi-row `INSERT … VALUES (?,…),(?,…),…` executed directly on [driver], instead of one
     * `queries.insert(...)` execution per row. `importContent` calls this once per chapter (up to 176
     * rows for Psalm 119), so the per-row-execution loop was a real cost during first-install seeding.
     *
     * CRITICAL: Android 7 / SQLite 3.9.2 caps bound parameters at 999 (`SQLITE_MAX_VARIABLE_NUMBER`).
     * content_entity's insert has [CONTENT_INSERT_COLUMNS] (11) columns, so a chunk may hold at most
     * `999 / 11 = 90` rows — a single naive multi-row insert of a whole chapter could exceed 999 and
     * throw "too many SQL variables" on-device. Chunking is mandatory, not an optimization.
     */
    override fun insertNoReturn(vararg entities: ContentEntity) {
        entities.forEach { if (it.id != 0) throw InsertionException("Entity ID was not 0") }
        if (entities.isEmpty()) return
        val maxRowsPerChunk = 999 / CONTENT_INSERT_COLUMNS
        db.transaction {
            entities.asList().chunked(maxRowsPerChunk).forEach { chunk ->
                val rowPlaceholders = List(chunk.size) { "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)" }.joinToString(", ")
                val sql = "INSERT INTO content_entity " +
                    "(collection_fk, sort, start, v_end, label, selected_take_fk, text, format, type_fk, draft_number, bridged) " +
                    "VALUES $rowPlaceholders"
                driver.execute(identifier = null, sql = sql, parameters = chunk.size * CONTENT_INSERT_COLUMNS) {
                    var i = 0
                    chunk.forEach { e ->
                        bindLong(i++, e.collectionFk.toLong())
                        bindLong(i++, e.sort.toLong())
                        bindLong(i++, e.start.toLong())
                        bindLong(i++, e.end.toLong())
                        bindString(i++, e.labelKey)
                        bindLong(i++, e.selectedTakeFk?.toLong())
                        bindString(i++, e.text)
                        bindString(i++, e.format)
                        bindLong(i++, e.type_fk.toLong())
                        bindLong(i++, e.draftNumber.toLong())
                        bindBoolean(i++, e.bridged)
                    }
                }
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
