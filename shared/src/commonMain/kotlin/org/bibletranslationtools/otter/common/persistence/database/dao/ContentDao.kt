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
package org.bibletranslationtools.otter.common.persistence.database.dao

import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity

/**
 * The clean DAO contract (no jOOQ `DSLContext` parameter — atomicity is handled inside the
 * implementation via its own transactions). The SQLDelight backend implements this directly; the
 * jOOQ backend reaches it through a thin test adapter during the coexistence period. Repositories
 * migrate onto these interfaces in Phase 4.
 *
 * The raw-jOOQ `Select`-returning methods (`selectVerseByCollectionIdAndStart`,
 * `selectLinkableVerses`, `selectLinkableChapters`, `fetchContentByProjectSlug`) are intentionally
 * omitted; they are handled elsewhere.
 */
interface ContentDao {
    fun fetchByCollectionId(collectionId: Int): List<ContentEntity>
    fun fetchByCollectionIdAndStart(
        collectionId: Int,
        start: Int,
        types: Collection<ContentType>
    ): List<ContentEntity>
    fun fetchByCollectionIdAndType(collectionId: Int, type: ContentType): List<ContentEntity>
    fun fetchSources(entity: ContentEntity): List<ContentEntity>
    fun updateSources(entity: ContentEntity, sources: List<ContentEntity>)
    fun insert(entity: ContentEntity): Int
    fun insertNoReturn(vararg entities: ContentEntity)
    fun fetchById(id: Int): ContentEntity
    fun fetchAll(): List<ContentEntity>
    fun updateAll(entities: List<ContentEntity>)
    fun update(entity: ContentEntity)
    fun delete(entity: ContentEntity)
    fun deleteForCollection(chapterCollection: CollectionEntity, contentTypeId: Int? = null)
    fun linkDerivative(contentId: Int, sourceContentId: Int)
    fun copyContent(sourceId: Int, metadataId: Int)
    fun copyMetaContent(sourceId: Int, metadataId: Int)
    fun linkDerivativeContent(sourceId: Int, projectId: Int)
    fun resourcesForContent(contentId: Int, dublinCoreId: Int): List<ContentEntity>
    fun resourcesForCollection(collectionId: Int, dublinCoreId: Int): List<ContentEntity>
}
