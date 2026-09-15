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

import org.bibletranslationtools.otter.common.persistence.entities.ResourceLinkEntity

/**
 * The clean DAO contract (no jOOQ `DSLContext` parameter — atomicity is handled inside the
 * implementation via its own transactions). The SQLDelight backend implements this directly; the
 * jOOQ backend reaches it through a thin test adapter during the coexistence period. Repositories
 * migrate onto these interfaces in Phase 4.
 *
 * The jOOQ `insertContentResourceNoReturn` / `insertCollectionResourceNoReturn` overloads take a raw
 * jOOQ `Select` and are handled outside this contract, so they are intentionally omitted here.
 */
interface ResourceLinkDao {
    fun fetchByContentId(id: Int): List<ResourceLinkEntity>
    fun fetchByCollectionId(id: Int): List<ResourceLinkEntity>
    fun insert(entity: ResourceLinkEntity): Int
    fun insertNoReturn(vararg entities: ResourceLinkEntity)
    fun fetchById(id: Int): ResourceLinkEntity
    fun fetchAll(): List<ResourceLinkEntity>
    fun update(entity: ResourceLinkEntity)
    fun delete(entity: ResourceLinkEntity)
    fun copyResourceLinks(sourceMetadataId: Int, derivedMetadataId: Int, projectId: Int, projectDublinCoreFk: Int)
    fun insertLinkableVerses(
        dublinCoreId: Int,
        parentCollectionId: Int,
        mainTypeIds: Collection<Int>,
        helpTypeIds: Collection<Int>,
    )
    fun insertLinkableChapters(dublinCoreId: Int, collectionId: Int, helpTypeIds: Collection<Int>)
    fun contentResourceMetadataFksByCollection(collectionId: Int): List<Int>
}
