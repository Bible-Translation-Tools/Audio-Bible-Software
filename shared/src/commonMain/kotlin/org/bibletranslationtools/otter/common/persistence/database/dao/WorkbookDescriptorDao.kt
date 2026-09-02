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

import org.bibletranslationtools.otter.common.persistence.entities.WorkbookDescriptorEntity

/**
 * The clean DAO contract (no jOOQ `DSLContext` parameter — atomicity is handled inside the
 * implementation via its own transactions). The SQLDelight backend implements this directly; the
 * jOOQ backend reaches it through a thin test adapter during the coexistence period. Repositories
 * migrate onto these interfaces in Phase 4.
 */
interface WorkbookDescriptorDao {
    fun fetch(sourceId: Int, targetId: Int, typeId: Int): WorkbookDescriptorEntity?
    fun fetchById(id: Int): WorkbookDescriptorEntity?
    fun fetchAll(): List<WorkbookDescriptorEntity>
    fun insert(entity: WorkbookDescriptorEntity): Int
    fun update(entity: WorkbookDescriptorEntity)
    fun delete(entity: WorkbookDescriptorEntity)
}
