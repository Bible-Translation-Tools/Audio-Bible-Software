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

import org.bibletranslationtools.otter.common.persistence.database.dao.VersificationDao
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [VersificationDao]. Mirrors the jOOQ VersificationDao, including its
 * exception-driven upsert (attempt insert, fall back to update on failure).
 */
internal class SqlDelightVersificationDao(private val db: OtterDatabase) : VersificationDao {
    private val queries = db.versificationQueries

    override fun fetchVersificationFile(slug: String): String? =
        queries.fetchVersificationFile(slug).executeAsOneOrNull()

    override fun insert(slug: String, path: String) {
        queries.insert(slug, path)
    }

    override fun update(slug: String, path: String) {
        queries.updateBySlug(path, slug)
    }

    override fun upsert(slug: String, path: String) {
        try {
            insert(slug, path)
        } catch (e: Exception) {
            update(slug, path)
        }
    }
}
