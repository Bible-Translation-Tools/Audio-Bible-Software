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

import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity

/**
 * The clean DAO contract (no jOOQ `DSLContext` parameter — atomicity is handled inside the
 * implementation via its own transactions). The SQLDelight backend implements this directly; the
 * jOOQ backend reaches it through a thin test adapter during the coexistence period. Repositories
 * migrate onto these interfaces in Phase 4.
 */
interface ResourceMetadataDao {
    fun exists(languageId: Int, identifier: String, version: String, creator: String): Boolean
    fun fetch(languageId: Int, identifier: String, version: String, creator: String): ResourceMetadataEntity?
    fun fetchLinks(entityId: Int): List<ResourceMetadataEntity>
    fun addLink(entity1Id: Int, entity2Id: Int)
    fun removeLink(entity1Id: Int, entity2Id: Int)
    fun insert(entity: ResourceMetadataEntity): Int
    fun fetchById(id: Int): ResourceMetadataEntity?
    fun fetchByIds(ids: List<Int>): List<ResourceMetadataEntity>
    fun fetchLatestVersion(
        languageSlug: String,
        identifier: String,
        creator: String,
        derivedFromFk: Int?,
        relaxCreatorIfNoMatch: Boolean = true,
    ): ResourceMetadataEntity?
    fun fetchLatestVersion(languageSlug: String, identifier: String): ResourceMetadataEntity?
    fun fetchAll(): List<ResourceMetadataEntity>
    fun update(entity: ResourceMetadataEntity)
    fun delete(entity: ResourceMetadataEntity)
    fun resourceMetadataByContent(contentId: Int): List<ResourceMetadataEntity>
    fun resourceMetadataByCollection(collectionId: Int): List<ResourceMetadataEntity>
    fun subtreeResourceMetadata(collectionId: Int): List<ResourceMetadataEntity>
}
