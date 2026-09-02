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

/**
 * The clean DAO contract for the content_type enum cache (no jOOQ `DSLContext` parameter). The
 * SQLDelight backend implements this directly, lazily seeding missing enum rows just like the jOOQ
 * backend does.
 */
interface ContentTypeDao {
    /** This value's ID in database table content_type. */
    fun fetchId(contentType: ContentType): Int

    /** Get value by ID in database table content_type. */
    fun fetchForId(databaseId: Int): ContentType?
}
