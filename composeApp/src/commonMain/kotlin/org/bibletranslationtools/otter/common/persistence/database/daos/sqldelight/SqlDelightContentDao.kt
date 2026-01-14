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
import org.bibletranslationtools.otter.common.persistence.database.Content_entity
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.ContentEntity
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.CollectionEntity

class SqlDelightContentDao(
    private val db: AppDatabase
) {
    private val queries = db.appDatabaseQueries

    fun fetchSources(entity: ContentEntity): List<ContentEntity> {
        return queries.fetchContentSources(entity.id.toLong()).executeAsList().map { it.toContentEntity() }
    }

    fun fetchSources(projectSlug: String): List<ContentEntity> {
        return queries.fetchContentByProjectSlug(projectSlug).executeAsList().map { it.toContentEntity() }
    }

    fun updateSources(entity: ContentEntity, sources: List<ContentEntity>) {
        db.transaction {
            queries.deleteDerivativesForContent(entity.id.toLong())
            sources.forEach { source ->
                queries.insertDerivative(entity.id.toLong(), source.id.toLong())
            }
        }
    }

    fun insert(entity: ContentEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID was not 0")

        queries.insertContent(
            collection_fk = entity.collectionFk.toLong(),
            sort = entity.sort.toLong(),
            start = entity.start.toLong(),
            v_end = entity.end.toLong(),
            label = entity.labelKey,
            selected_take_fk = entity.selectedTakeFk?.toLong(),
            text = entity.text,
            format = entity.format,
            type_fk = entity.type_fk.toLong(),
            draft_number = entity.draftNumber.toLong(),
            bridged = if (entity.bridged) 1L else 0L
        )

        return queries.lastInsertId().executeAsOne().toInt()
    }

    fun insertNoReturn(vararg entities: ContentEntity) {
        db.transaction {
            entities.forEach { e ->
                if (e.id != 0) throw InsertionException("Entity ID was not 0")
                queries.insertContent(
                    collection_fk = e.collectionFk.toLong(),
                    sort = e.sort.toLong(),
                    start = e.start.toLong(),
                    v_end = e.end.toLong(),
                    label = e.labelKey,
                    selected_take_fk = e.selectedTakeFk?.toLong(),
                    text = e.text,
                    format = e.format,
                    type_fk = e.type_fk.toLong(),
                    draft_number = e.draftNumber.toLong(),
                    bridged = if (e.bridged) 1L else 0L
                )
            }
        }
    }

    fun fetchById(id: Int): ContentEntity {
        return queries.fetchContentById(id.toLong()).executeAsOne().toContentEntity()
    }

    fun fetchAll(): List<ContentEntity> {
        return queries.fetchAllContent().executeAsList().map { it.toContentEntity() }
    }

    fun updateAll(entities: List<ContentEntity>) {
        db.transaction {
            entities.forEach { entity ->
                queries.updateContent(
                    collection_fk = entity.collectionFk.toLong(),
                    sort = entity.sort.toLong(),
                    start = entity.start.toLong(),
                    v_end = entity.end.toLong(),
                    label = entity.labelKey,
                    selected_take_fk = entity.selectedTakeFk?.toLong(),
                    text = entity.text,
                    format = entity.format,
                    type_fk = entity.type_fk.toLong(),
                    draft_number = entity.draftNumber.toLong(),
                    bridged = if (entity.bridged) 1L else 0L,
                    id = entity.id.toLong()
                )
            }
        }
    }

    fun update(entity: ContentEntity) {
        queries.updateContent(
            collection_fk = entity.collectionFk.toLong(),
            sort = entity.sort.toLong(),
            start = entity.start.toLong(),
            v_end = entity.end.toLong(),
            label = entity.labelKey,
            selected_take_fk = entity.selectedTakeFk?.toLong(),
            text = entity.text,
            format = entity.format,
            type_fk = entity.type_fk.toLong(),
            draft_number = entity.draftNumber.toLong(),
            bridged = if (entity.bridged) 1L else 0L,
            id = entity.id.toLong()
        )
    }

    fun delete(entity: ContentEntity) {
        queries.deleteContentById(entity.id.toLong())
    }

    fun deleteForCollection(
        chapterCollection: CollectionEntity,
        contentTypeId: Int? = null
    ) {
        queries.deleteContentForCollection(chapterCollection.id.toLong(), (contentTypeId ?: 1).toLong())
    }

    fun linkDerivative(
        contentId: Int,
        sourceContentId: Int
    ) {
        queries.insertDerivative(contentId.toLong(), sourceContentId.toLong())
    }
}

fun Content_entity.toContentEntity(): ContentEntity {
    return ContentEntity(
        id = id.toInt(),
        sort = sort.toInt(),
        labelKey = label,
        start = start.toInt(),
        end = v_end.toInt(),
        collectionFk = collection_fk.toInt(),
        selectedTakeFk = selected_take_fk?.toInt(),
        text = text,
        format = format,
        type_fk = type_fk.toInt(),
        draftNumber = draft_number.toInt(),
        bridged = bridged == 1L
    )
}
